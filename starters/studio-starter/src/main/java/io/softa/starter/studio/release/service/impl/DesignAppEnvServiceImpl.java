package io.softa.starter.studio.release.service.impl;

import java.io.Serializable;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.web.signature.Ed25519Keys;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.release.dto.DesignAggregate;
import io.softa.starter.studio.release.connector.Connector;
import io.softa.starter.studio.release.connector.ConnectorFactory;
import io.softa.starter.studio.release.desired.*;
import io.softa.starter.studio.release.dto.AggregateChangeReport;
import io.softa.starter.studio.release.dto.DriftEnvelopeDTO;
import io.softa.starter.studio.release.dto.DriftReportMapper;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignActivity;
import io.softa.starter.studio.release.entity.DesignApp;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignSnapshot;
import io.softa.starter.studio.release.enums.*;
import io.softa.starter.studio.release.service.DesignActivityService;
import io.softa.starter.studio.release.service.DesignAppEnvService;
import io.softa.starter.studio.release.service.DesignAppService;
import io.softa.starter.studio.release.service.DesignSnapshotService;

/**
 * DesignAppEnv Model Service Implementation.
 * <p>
 * Provides per-env publish (design→runtime converge), design-vs-runtime drift comparison, and
 * runtime→design-time drift import.
 * <p>
 * Drift / publish baseline: there is no snapshot — both diff the env's live
 * {@code design_*} rows directly against its runtime catalog by business key
 * ({@link io.softa.starter.studio.release.desired.DesignAggregateDiffer}). The runtime keys by
 * business code and never stores the design surrogate id, so an id-keyed snapshot↔runtime baseline
 * was unsound and is retired.
 * <p>
 * Runtime-side reads/writes always go through the env's
 * {@link io.softa.starter.studio.release.connector.Connector} (for a Softa runtime it wraps
 * the signed {@code RemoteApiClient}). The earlier "local env" shortcut was removed because matching
 * Spring active profiles against {@code envType.name()} collided in practice — at scale many envs share
 * the same type or name, so the heuristic would occasionally short-circuit and read local metadata that
 * belongs to a different environment. See {@code feedback_studio_always_remote_deploy.md} for the full
 * incident context.
 */
@Slf4j
@Service
public class DesignAppEnvServiceImpl extends EntityServiceImpl<DesignAppEnv, Long> implements DesignAppEnvService {

    // Env-scope column — the cascade-delete filters an env's design_* rows by it.
    private static final String ENV_ID = LambdaUtils.getAttributeName(DesignModel::getEnvId);

    /**
     * The per-env {@code design_*} meta-models that make up an env's workspace, in <b>child→parent</b>
     * order ({@link DesignAggregate#deleteOrder()}) so {@link #deleteByIds} drops children before their
     * parent. The same per-env design set {@link DesignEnvSource} loads and {@link DesignEnvCloner} clones.
     */
    private static final List<String> DESIGN_WORKSPACE_MODELS_CHILD_FIRST =
            DesignAggregate.deleteOrder().stream().map(DesignAggregate::designName).toList();

    private final DesignAppService appService;
    private final ModelService<Serializable> modelService;
    private final DesignEnvCloner envCloner;
    private final DesignEnvSource envSource;
    private final DesiredStateConverger converger;
    private final DesiredStateDeployService desiredStateDeployService;
    private final ConnectorFactory connectorFactory;
    private final DesignEnvMerger envMerger;
    private final DesignActivityService activityService;
    private final DesignSnapshotService snapshotService;
    private final DesignAggregateDiffer aggregateDiffer;
    private final DesignDriftImporter driftImporter;

    /** Constructor injection; {@code appService} is {@code @Lazy} to break the app↔env service cycle. */
    public DesignAppEnvServiceImpl(@Lazy DesignAppService appService,
                                   ModelService<Serializable> modelService,
                                   DesignEnvCloner envCloner,
                                   DesignEnvSource envSource,
                                   DesiredStateConverger converger,
                                   DesiredStateDeployService desiredStateDeployService,
                                   ConnectorFactory connectorFactory,
                                   DesignEnvMerger envMerger,
                                   DesignActivityService activityService,
                                   DesignSnapshotService snapshotService,
                                   DesignAggregateDiffer aggregateDiffer,
                                   DesignDriftImporter driftImporter) {
        this.appService = appService;
        this.modelService = modelService;
        this.envCloner = envCloner;
        this.envSource = envSource;
        this.converger = converger;
        this.desiredStateDeployService = desiredStateDeployService;
        this.connectorFactory = connectorFactory;
        this.envMerger = envMerger;
        this.activityService = activityService;
        this.snapshotService = snapshotService;
        this.aggregateDiffer = aggregateDiffer;
        this.driftImporter = driftImporter;
    }

    // ----------------------------------------------------------------- delete (env + its design workspace)

    /**
     * Delete environment(s) together with their full per-env design workspace, in one transaction and
     * children-before-parents (per-env design): an env and its {@code design_*} rows share a
     * lifecycle, mirroring the {@code DesignModel} → field/index cascade in
     * {@link io.softa.starter.studio.meta.service.impl.DesignModelServiceImpl#deleteByIds}.
     *
     * <p>{@code DesignAppEnv} is a hard-delete model; there is no DB foreign key from the {@code design_*}
     * tables to the env and the ORM does not cascade {@code env_id}, so without this a deleted env left its
     * models / fields / indexes / option-sets / items behind with a dangling {@code env_id}. Those orphans
     * are silently excluded by the publish/merge differ ({@link DesignAggregateDiffer}) but never cleaned
     * up, and they break per-env consumers that resolve the env from a design row — e.g.
     * {@code DesignModelServiceImpl.previewDDL}, which throws once its env is gone.
     *
     * <p>A {@code protectedEnv} env is refused outright (the whole batch rolls back) — clearing the flag is
     * the explicit opt-in before an env's workspace and its runtime binding (signing keypair, upgrade
     * endpoint) can be torn down. The cascade is scoped by {@code env_id} — a globally unique distributed id
     * that belongs to exactly one app — so it can never reach another env's rows.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return super.deleteByIds(ids);
        }
        rejectProtectedEnvs(ids);
        cascadeDeleteDesignWorkspace(ids);
        return super.deleteByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        return this.deleteByIds(Collections.singletonList(id));
    }

    /**
     * Fail-closed guard: a {@code protectedEnv} env cannot be deleted until its flag is cleared. Throws on
     * the first protected env in the batch, so one protected member aborts the whole delete before any row
     * is removed. Non-existent ids are simply absent from the load (nothing to protect; the cascade still
     * sweeps any rows that carry their {@code env_id}).
     */
    private void rejectProtectedEnvs(List<Long> ids) {
        for (DesignAppEnv env : this.getByIds(ids)) {
            if (Boolean.TRUE.equals(env.getProtectedEnv())) {
                throw new IllegalArgumentException(
                        "Env {0} is protected and cannot be deleted; clear its protected flag first.",
                        env.getName());
            }
        }
    }

    /** Remove the envs' per-env {@code design_*} rows (children before parents), scoped by {@code envId}. */
    private void cascadeDeleteDesignWorkspace(List<Long> ids) {
        for (String designModel : DESIGN_WORKSPACE_MODELS_CHILD_FIRST) {
            modelService.deleteByFilters(designModel, new Filters().in(ENV_ID, ids));
        }
    }

    /**
     * The deploy-direction design↔runtime diff for this env, computed <b>on demand</b> (no stored
     * drift cache). Returns the {@link DesignAggregateDiffer} change set; empty when in sync.
     * The expensive five-table runtime fan-out is fronted by the R5 checksum gate
     * ({@link DesiredStateConverger#computeChanges} via {@link #computeDrift}).
     * <p>
     * Package-private (not on the {@link DesignAppEnvService} interface) — the only callers are
     * {@link #applyDrift(Long)} and the same-package test. The UI's drift view goes through
     * {@link #getDriftEnvelope(Long)}.
     */
    List<RowChangeDTO> compareDesignWithRuntime(Long envId) {
        Assert.notNull(envId, "envId must not be null");
        return computeDrift(loadEnv(envId));
    }

    /**
     * Operator-perspective drift view for the UI, computed on demand. Reshapes the deploy-direction diff
     * into expected/actual rows tagged with {@link io.softa.starter.studio.release.enums.DriftKind}. A
     * runtime-unreachable / diff failure surfaces as a {@code FAILURE} envelope rather than propagating.
     */
    @Override
    public DriftEnvelopeDTO getDriftEnvelope(Long envId) {
        Assert.notNull(envId, "envId must not be null");
        DesignAppEnv env = loadEnv(envId);
        try {
            List<RowChangeDTO> drift = computeDrift(env);
            return DriftEnvelopeDTO.builder()
                    .envId(envId)
                    .checkStatus(DesignDriftCheckStatus.SUCCESS)
                    .lastCheckedTime(LocalDateTime.now())
                    .hasDrift(!drift.isEmpty())
                    .reports(DriftReportMapper.toReport(drift))
                    .build();
        } catch (RuntimeException e) {
            log.error("Drift check failed for env {}: {}", envId, e.getMessage(), e);
            return DriftEnvelopeDTO.builder()
                    .envId(envId)
                    .checkStatus(DesignDriftCheckStatus.FAILURE)
                    .errorMessage(e.getMessage())
                    .lastCheckedTime(LocalDateTime.now())
                    .build();
        }
    }

    /** On-demand design↔runtime drift (the R5-gated business-key diff). No persistence. */
    private List<RowChangeDTO> computeDrift(DesignAppEnv env) {
        String appCode = resolveAppCode(env);
        DesignRows design = envSource.load(env.getAppId(), env.getId());
        return converger.computeChanges(connectorFactory.forEnv(env), appCode, design);
    }

    /**
     * Runtime-drift preview: how the runtime has drifted from what was last deployed —
     * <b>runtime vs the last PUBLISH snapshot</b> (not vs design). Returns an {@link AggregateChangeReport}
     * reading base(snapshot) → after(runtime): a row only on the runtime is a drift-add, a row only in the
     * snapshot is a drift-remove, a changed row carries before(snapshot)/after(runtime). Read-only, for the
     * UI — deploy still converges to the live runtime; this is just "what changed out of band since the last
     * deploy". An empty report when the env has never been published (no baseline). A physical (JDBC) runtime
     * cannot observe option sets, so they are excluded (same option-set data-loss guard as reverse) rather
     * than reported as removed. Orthogonal to {@link #compareDesignWithRuntime} (design↔runtime, the deploy-preview lens).
     */
    @Override
    public AggregateChangeReport previewRuntimeDrift(Long envId) {
        Assert.notNull(envId, "envId must not be null");
        DesignAppEnv env = loadEnv(envId);
        DesignRows base = lastPublishSnapshot(env.getId());
        if (base == null) {
            return new AggregateChangeReport(List.of());   // never deployed → no baseline to drift from
        }
        DesignRows runtime = connectorFactory.forEnv(env).readSchema(resolveAppCode(env));
        // diff(desired=runtime, observed=snapshot): fullRow=runtime (after), previousValues=snapshot (before).
        List<RowChangeDTO> drift = aggregateDiffer.diff(runtime, base);
        if (env.getConnectorType() == ConnectorType.JDBC) {
            drift = withoutOptionSets(drift);
        }
        return AggregateChangeReport.from(drift);
    }

    /** The {@link DesignRows} captured by this env's most recent succeeded PUBLISH, or {@code null} if none. */
    private DesignRows lastPublishSnapshot(Long envId) {
        Filters filters = new Filters()
                .eq(DesignActivity::getEnvId, envId)
                .eq(DesignActivity::getKind, DesignActivityKind.PUBLISH)
                .eq(DesignActivity::getStatus, DesignActivityStatus.SUCCESS);
        // DesignActivity is ordered id DESC (defaultOrder), so the first carrying a snapshot is the latest.
        for (DesignActivity activity : activityService.searchList(filters)) {
            if (activity.getSnapshotId() != null) {
                return snapshotService.getById(activity.getSnapshotId())
                        .map(snapshot -> JsonUtils.jsonNodeToObject(snapshot.getContent(), DesignRows.class))
                        .orElse(null);
            }
        }
        return null;
    }

    private DesignAppEnv loadEnv(Long envId) {
        return this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));
    }

    /** Guard shared by seed / merge: the two envs must be distinct and belong to the same app. */
    private static void requireDistinctSameApp(DesignAppEnv source, DesignAppEnv target) {
        if (source.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Source and target env must differ! {0}", target.getId());
        }
        if (!Objects.equals(source.getAppId(), target.getAppId())) {
            throw new IllegalArgumentException(
                    "Source env {0} and target env {1} belong to different apps.", source.getId(), target.getId());
        }
    }

    /**
     * Issue a new Ed25519 keypair for the env.
     * <p>
     * Generation uses the JDK 25 {@code KeyPairGenerator}. Each runtime trusts
     * exactly one signer, so reissuing here is an atomic replacement — the
     * operator then swaps {@code system.metadata.public-key} on the
     * runtime side to match.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IssuedKey issueKey(Long envId) {
        DesignAppEnv env = loadEnv(envId);

        KeyPair keyPair = Ed25519Keys.generate();
        String encodedPublicKey = Ed25519Keys.encodePublicKey(keyPair.getPublic());
        String encodedPrivateKey = Ed25519Keys.encodePrivateKey(keyPair.getPrivate());

        env.setPublicKey(encodedPublicKey);
        env.setPrivateKey(encodedPrivateKey);
        this.updateOne(env);

        return new IssuedKey(encodedPublicKey);
    }

    /**
     * Seed (clone) a target env's design from a source env (per-env design). Idempotent:
     * refuses to clobber a target that already owns design rows. Source and target must share an app.
     * The cloner mints fresh per-env ids, remaps parent FKs, and copies each source row's business key verbatim.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int seedFromSource(Long targetEnvId, Long sourceEnvId) {
        DesignAppEnv target = loadEnv(targetEnvId);
        DesignAppEnv source = loadEnv(sourceEnvId);
        requireDistinctSameApp(source, target);

        Long appId = target.getAppId();
        // Idempotent / non-destructive: never clobber an env that already owns design rows.
        if (envCloner.countModels(appId, targetEnvId) > 0) {
            log.info("seedFromSource: env {} already has design rows — skipping seed from env {}.",
                    targetEnvId, sourceEnvId);
            return 0;
        }
        int created = envCloner.cloneEnv(appId, sourceEnvId, targetEnvId);
        log.info("seedFromSource: cloned {} design row(s) from env {} into env {}.",
                created, sourceEnvId, targetEnvId);
        return created;
    }

    /**
     * Merge {@code sourceEnvId}'s design into {@code targetEnvId} (Phase 3). Single
     * transaction under the target env mutex ({@code MERGING}); converges the target to the source by
     * business key via {@link DesignEnvMerger} and records a {@link DesignActivity} of kind {@code MERGE}.
     * Design↔design only — no remote RPC — so a failure rolls back wholesale. A {@code null}/empty
     * {@code selection} is a full merge.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long merge(Long sourceEnvId, Long targetEnvId, MergeSelection selection) {
        DesignAppEnv source = loadEnv(sourceEnvId);
        DesignAppEnv target = loadEnv(targetEnvId);
        requireDistinctSameApp(source, target);

        acquireEnvLock(target, DesignAppEnvStatus.MERGING);
        try {
            DesignEnvMerger.MergeResult result = envMerger.merge(target.getAppId(), sourceEnvId, targetEnvId, selection);
            Long mergeId = recordMerge(source, target, result);
            log.info("merge: env {} -> env {} applied (+{} ~{} -{}).",
                    sourceEnvId, targetEnvId, result.created(), result.updated(), result.deleted());
            return mergeId;
        } finally {
            releaseEnvLock(target);
        }
    }

    private Long recordMerge(DesignAppEnv source, DesignAppEnv target, DesignEnvMerger.MergeResult result) {
        DesignActivity activity = activityService.start(
                target.getAppId(), target.getId(), DesignActivityKind.MERGE, source.getId(), operatorId());
        // changeSet = the applied per-row diff (with before/after), uniform with PUBLISH/IMPORT/REVERSE.
        // detail = null: the created/updated/deleted counts are derivable from changeSet
        // by op, so they are not stored (no cheaply-derived values).
        activityService.succeed(activity.getId(),
                JsonUtils.objectToJsonNode(result.changes()),
                null,
                snapshotDesign(activity.getId(), target));
        return activity.getId();
    }

    /**
     * Publish env {@code envId}'s design to its runtime ({@code publish(envId)}): converge the
     * runtime catalog to the env's per-env design rows.
     * <p>
     * The diff is <b>business-key</b> design↔runtime ({@link DesignAggregateDiffer}), not surrogate-id —
     * the runtime apply strips the design id and keys by business code, so the runtime never carries
     * the design id. The change set drives rename-aware DDL + whole-aggregate overwrite shipped via
     * {@code applyDesiredAggregates} (the shared apply half on {@link DesiredStateDeployService}).
     * Renames currently degrade to drop+add — in-place rename is Phase 4 (recorded {@code old*Name}
     * + a runtime-side rename UPDATE).
     * <p>
     * Orchestration: acquire the per-env mutex (CAS {@code STABLE}→{@code DEPLOYING}), open a
     * {@link DesignActivity} of kind {@code PUBLISH}, run {@link #publishInternal} inline, then mark the
     * activity {@code SUCCESS}/{@code FAILURE}; the mutex is released in a
     * finally. NOT {@code @Transactional}: the mutex must stay committed for the remote apply and each
     * transition commits eagerly (roll-forward; a retry re-converges).
     */
    @Override
    public void publish(Long envId) {
        DesignAppEnv env = loadEnv(envId);
        acquireEnvLock(env, DesignAppEnvStatus.DEPLOYING);
        DesignActivity activity = activityService.start(
                env.getAppId(), env.getId(), DesignActivityKind.PUBLISH, null, operatorId());
        try {
            PublishResult result = publishInternal(env);
            activityService.succeed(activity.getId(),
                    JsonUtils.objectToJsonNode(result.changes()),
                    JsonUtils.objectToJsonNode(Map.of("mergedDdl", result.fullDdl())),
                    snapshotDesign(activity.getId(), env));
        } catch (RuntimeException e) {
            log.error("Publish failed: envId={}, activityId={}", env.getId(), activity.getId(), e);
            activityService.fail(activity.getId(), e.getMessage());
            throw e;
        } finally {
            releaseEnvLock(env);
        }
    }

    /**
     * Retry a FAILED publish by re-publishing its env. Operates on the
     * {@link DesignActivity} audit record.
     */
    @Override
    public void retryPublish(Long activityId) {
        DesignActivity activity = activityService.getById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity does not exist! {0}", activityId));
        Assert.isEqual(activity.getKind(), DesignActivityKind.PUBLISH,
                "Only PUBLISH activities can be retried! Kind: {0}", activity.getKind());
        Assert.isEqual(activity.getStatus(), DesignActivityStatus.FAILURE,
                "Only FAILURE activities can be retried! Status: {0}", activity.getStatus());
        publish(activity.getEnvId());
    }

    /**
     * Cancel a stuck (RUNNING) publish activity and release its env mutex. NO automatic
     * rollback (roll-forward only) — an operator escape hatch for an env pinned in {@code DEPLOYING}.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelPublish(Long activityId) {
        DesignActivity activity = activityService.getById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity does not exist! {0}", activityId));
        Assert.isEqual(activity.getStatus(), DesignActivityStatus.RUNNING,
                "Only RUNNING activities can be cancelled! Status: {0}", activity.getStatus());
        activityService.cancel(activityId, "Cancelled by operator; no automatic rollback — revert runtime "
                + "state manually if partial changes were committed before cancellation.");
        // Force-release the env mutex back to STABLE (roll-forward escape hatch for a pinned env).
        releaseEnvLock(loadEnv(activity.getEnvId()));
    }

    /**
     * Roll an env back to a prior activity's captured design: overwrite the env's
     * per-env design from the activity's {@link DesignSnapshot} (committed under the env mutex), then
     * {@link #publish} to converge the runtime. Two phases on purpose — the design overwrite must commit
     * before publish does its remote I/O (publish runs outside any transaction).
     */
    @Override
    public void restore(Long activityId) {
        DesignActivity activity = activityService.getById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity does not exist! {0}", activityId));
        // Any succeeded activity that captured a snapshot is restorable: PUBLISH / MERGE /
        // IMPORT / REVERSE all snapshot their post-op design; the snapshot is the restorable artifact.
        Assert.isEqual(activity.getStatus(), DesignActivityStatus.SUCCESS,
                "Only succeeded activities can be restored! Status: {0}", activity.getStatus());
        Assert.notNull(activity.getSnapshotId(),
                "Activity {0} has no snapshot to restore.", activityId);
        DesignSnapshot snapshot = snapshotService.getById(activity.getSnapshotId())
                .orElseThrow(() -> new IllegalArgumentException("Snapshot does not exist! {0}", activity.getSnapshotId()));
        DesignRows design = JsonUtils.jsonNodeToObject(snapshot.getContent(), DesignRows.class);

        DesignAppEnv env = loadEnv(activity.getEnvId());
        acquireEnvLock(env);
        try {
            envCloner.replaceEnvDesign(env.getId(), design);
        } finally {
            releaseEnvLock(env);
        }
        // Converge the runtime to the restored design (records its own PUBLISH activity + snapshot).
        publish(env.getId());
    }

    /** Capture the env's current per-env design ({@code DesignRows}) as this activity's restore snapshot. */
    private Long snapshotDesign(Long activityId, DesignAppEnv env) {
        return activityService.snapshot(activityId,
                JsonUtils.objectToJsonNode(envSource.load(env.getAppId(), env.getId())));
    }

    /**
     * The lock-free converge core: env-design ↔ runtime business-key diff → rename-aware DDL +
     * incremental apply. Internal helper of {@link #publish(Long)} (its only caller), which already holds
     * the env mutex. Returns the change set + rendered DDL for the activity audit record. Package-private
     * (not on the service interface) — there is no external caller. Renames degrade to drop+add.
     */
    PublishResult publishInternal(DesignAppEnv env) {
        String appCode = resolveAppCode(env);
        DesignRows design = envSource.load(env.getAppId(), env.getId());
        // The env's connector owns all runtime touch — the checksum gate, the schema read, the
        // DDL dialect (Softa runtime → builtin annotation dialect, identical to the boot scanner), and the
        // apply. Built once and threaded through the gate + the apply.
        Connector connector = connectorFactory.forEnv(env);
        List<RowChangeDTO> changes = converger.computeChanges(connector, appCode, design);
        if (changes.isEmpty()) {
            // Nothing to publish — interrupt loudly rather than record an empty no-op deployment.
            throw new IllegalArgumentException(
                    "Env {0} is already in sync with its runtime; there is nothing to publish.", env.getId());
        }
        DesiredStateDeployService.Applied applied =
                desiredStateDeployService.applyToRuntime(env, appCode, connector, changes);
        return new PublishResult(changes, applied.combinedDdl());
    }

    /** The change set + full rendered DDL produced by {@link #publishInternal} — internal audit carrier. */
    record PublishResult(List<RowChangeDTO> changes, String fullDdl) {
    }

    /** Resolve the app's stable {@code appCode} — the cross-system key the runtime verifies. */
    private String resolveAppCode(DesignAppEnv env) {
        Assert.notNull(env.getAppId(), "Env {0} has no owning app.", env.getId());
        String appCode = appService.getFieldValue(env.getAppId(), DesignApp::getAppCode);
        Assert.notBlank(appCode, "DesignApp {0} has no appCode; cannot address its runtime.",
                env.getAppId());
        return appCode;
    }

    /**
     * Overwrite design-time metadata with the current state of this env's target, inverting the drift onto
     * the env's per-env design rows. Two flavours, by the env's connector:
     * <ul>
     *   <li><b>Import</b> (Softa connector) — reverse the runtime {@code sys_*} catalog (full fidelity);
     *       records a {@code kind=IMPORT} activity.</li>
     *   <li><b>Reverse</b> (JDBC connector) — reverse the raw physical schema (structural only); records a
     *       {@code kind=REVERSE} activity. A physical source has <b>no option sets</b>, so its readSchema
     *       reports none — the inverted drift would otherwise read that "absent" as "delete the design's
     *       option sets" (option-set data-loss guard): option-set / option-item changes are filtered out so
     *       a reverse never touches the design's logical option sets.</li>
     * </ul>
     * Runs under the env mutex (the shared env-status guard — see {@link #acquireEnvLock} for its limits) and
     * {@code @Transactional} (the design rewrite + its audit record commit atomically; a failure rolls back to a clean no-op).
     * No-op (no activity) when already in sync. The post-op design is snapshotted so the activity is
     * restorable.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDrift(Long envId) {
        DesignAppEnv env = loadEnv(envId);
        acquireEnvLock(env);
        try {
            List<RowChangeDTO> drift = compareDesignWithRuntime(envId);
            // A physical (JDBC) source cannot observe option sets — never let its "absent" delete them.
            boolean physicalOnly = env.getConnectorType() == ConnectorType.JDBC;
            List<RowChangeDTO> applicable = physicalOnly ? withoutOptionSets(drift) : drift;
            if (applicable.isEmpty()) {
                return;   // already in sync — no-op, no audit record
            }
            DesignActivityKind kind = physicalOnly ? DesignActivityKind.REVERSE : DesignActivityKind.IMPORT;
            DesignActivity activity = activityService.start(env.getAppId(), env.getId(), kind, null, operatorId());
            driftImporter.apply(applicable, env);
            activityService.succeed(activity.getId(), JsonUtils.objectToJsonNode(applicable),
                    null, snapshotDesign(activity.getId(), env));
        } finally {
            releaseEnvLock(env);
        }
    }

    /** Drop option-set / option-item changes — a physical reverse cannot observe them (data-loss guard). */
    private static List<RowChangeDTO> withoutOptionSets(List<RowChangeDTO> drift) {
        return drift.stream()
                .filter(row -> row.getTable() != MetaTable.OPTION_SET && row.getTable() != MetaTable.OPTION_ITEM)
                .toList();
    }

    /**
     * Acquire the per-env mutex on {@code envStatus}. The mutex is shared with the deployment path, so
     * this refuses to proceed while a deploy is in flight — import and deploy cannot be concurrent on the
     * same env. See {@link #acquireEnvLock(DesignAppEnv, DesignAppEnvStatus)} for the guard's known limits.
     */
    private void acquireEnvLock(DesignAppEnv appEnv) {
        acquireEnvLock(appEnv, DesignAppEnvStatus.IMPORTING);
    }

    /**
     * The one per-env mutex acquire ({@code STABLE} → {@code busyStatus}): publish passes {@code DEPLOYING},
     * import/restore {@code IMPORTING}.
     * <p>
     * This is an <b>atomic optimistic compare-and-set</b> on the env's {@code version} ({@code versionLock}):
     * {@code updateOne} emits a single {@code UPDATE … SET env_status=?, version=version+1 WHERE id=? AND
     * version=?}, so two operations that both read the same version cannot both win — the loser's update
     * matches zero rows and {@code updateOne} throws {@link VersionException}, which we translate into a
     * "busy" refusal. The in-memory {@code envStatus == STABLE} pre-check refuses an env that is already busy
     * (e.g. a stuck lock) before the CAS is even attempted. The framework bumps {@code version} on success,
     * mirrored on the in-memory row so the matching {@link #releaseEnvLock} (also {@code versionLock}-guarded)
     * lines up.
     */
    private void acquireEnvLock(DesignAppEnv appEnv, DesignAppEnvStatus busyStatus) {
        Assert.isTrue(appEnv.getEnvStatus() == DesignAppEnvStatus.STABLE,
                "Env {0} is currently Deploying or Importing. Retry later.", appEnv.getName());
        appEnv.setEnvStatus(busyStatus);
        boolean won;
        try {
            won = this.updateOne(appEnv);
        } catch (VersionException e) {
            won = false;
        }
        Assert.isTrue(won, "Env {0} is currently Deploying or Importing. Retry later.", appEnv.getName());
        if (appEnv.getVersion() != null) {
            appEnv.setVersion(appEnv.getVersion() + 1);
        }
    }

    /** Release the mutex back to {@code STABLE}. Version-guarded on the acquire-bumped version (single UPDATE). */
    private void releaseEnvLock(DesignAppEnv appEnv) {
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        this.updateOne(appEnv);
    }

    /** The current operator's user id (may be null in unauthenticated / system contexts). */
    private Long operatorId() {
        return ContextHolder.getContext().getUserId();
    }

}
