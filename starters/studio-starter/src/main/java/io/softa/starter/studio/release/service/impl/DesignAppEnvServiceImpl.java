package io.softa.starter.studio.release.service.impl;

import java.io.Serializable;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.web.signature.Ed25519Keys;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignAppEnvDrift;
import io.softa.starter.studio.release.entity.DesignAppEnvSnapshot;
import io.softa.starter.studio.release.entity.DesignAppVersion;
import io.softa.starter.studio.release.enums.DesignAppEnvStatus;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.enums.DesignDriftCheckStatus;
import io.softa.starter.studio.release.service.DesignAppEnvDriftService;
import io.softa.starter.studio.release.service.DesignAppEnvService;
import io.softa.starter.studio.release.service.DesignAppEnvSnapshotService;
import io.softa.starter.studio.release.service.DesignAppVersionService;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

/**
 * DesignAppEnv Model Service Implementation.
 * <p>
 * Provides snapshot management, design-vs-runtime comparison, and runtime→design-time
 * drift import.
 * <p>
 * Snapshot strategy: each successful deployment writes one snapshot row uniquely keyed by
 * {@code (appId, envId, deploymentId)}. The snapshot is built incrementally — the previous
 * snapshot is loaded as the baseline, then merged deployment changes are applied on top:
 * {@code currentSnapshot = latestSnapshot + mergedChanges}. Write-through uses
 * {@code createOrUpdate} so the operation is idempotent even if the upstream async listener
 * retries.
 * <p>
 * Runtime-side reads always go through {@link RemoteApiClient}. The earlier
 * "local env" shortcut was removed because matching Spring active profiles against
 * {@code envType.name()} collided in practice — at scale many envs share the same type
 * or name, so the heuristic would occasionally short-circuit and read local metadata
 * that belongs to a different environment. See
 * {@code feedback_studio_always_remote_deploy.md} for the full incident context.
 */
@Slf4j
@Service
public class DesignAppEnvServiceImpl extends EntityServiceImpl<DesignAppEnv, Long> implements DesignAppEnvService {

    /** Truncation bound for {@code errorMessage} so it matches the DB column width. */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

    /**
     * Dedicated virtual-thread executor for the parallel drift fan-out.
     * <p>
     * {@link CompletableFuture#supplyAsync(java.util.function.Supplier)} without an
     * explicit executor falls back to {@link java.util.concurrent.ForkJoinPool#commonPool()},
     * which is a platform-thread pool capped by CPU count. For I/O-bound fan-out (remote
     * metadata export per version-controlled model) that caps effective concurrency;
     * a virtual-thread executor lets every subtask launch immediately and unmount its
     * carrier during {@code Socket.read}, so all N RPCs fire in parallel.
     * <p>
     * Static because the executor holds no pooled resources — each task spawns a fresh
     * virtual thread that terminates when the task returns, so there is nothing to
     * shut down at bean destruction time.
     */
    private static final Executor DRIFT_VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    private DesignAppEnvSnapshotService snapshotService;

    @Autowired
    private DesignAppEnvDriftService driftService;

    @Autowired
    private RemoteApiClient remoteApiClient;

    @Lazy
    @Autowired
    private DesignAppVersionService appVersionService;

    @Autowired
    private ModelService<Serializable> modelService;

    /**
     * Take a snapshot of the expected runtime metadata state for the given environment.
     * <p>
     * Loads the most recent snapshot as the baseline (empty for first deployment), then
     * applies {@code mergedChanges} (CREATE / UPDATE / DELETE) on top to produce the new
     * full state. Each deployment stores its own snapshot row keyed by
     * {@code (appId, envId, deploymentId)} — that triple is guarded by a UNIQUE constraint
     * at the DB level, and persistence goes through {@code createOrUpdate} so re-runs of
     * the same deployment id are idempotent instead of creating duplicates (e.g. if the
     * async snapshot listener fires twice due to retries).
     *
     * @param envId         Environment ID
     * @param deploymentId  Deployment ID that produced this snapshot
     * @param mergedChanges the merged version changes that were deployed
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void takeSnapshot(Long envId, Long deploymentId, List<ModelChangesDTO> mergedChanges) {
        DesignAppEnv appEnv = this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        // Load the latest snapshot as baseline (empty map for first deployment)
        Map<String, List<Map<String, Object>>> baseline = findLatestSnapshotByEnvId(envId)
                .map(DesignAppEnvSnapshot::getSnapshot)
                .<Map<String, List<Map<String, Object>>>>map(node -> JsonUtils.jsonNodeToObject(node, new TypeReference<>() {}))
                .orElseGet(LinkedHashMap::new);

        // Apply mergedChanges on top of the baseline
        applyChangesToBaseline(baseline, mergedChanges);

        // Idempotent upsert keyed by (appId, envId, deploymentId). The matching UNIQUE index
        // on design_app_env_snapshot prevents duplicate rows if this listener is invoked
        // twice for the same deployment (e.g. async retry, replayed event).
        DesignAppEnvSnapshot snapshot = new DesignAppEnvSnapshot();
        snapshot.setAppId(appEnv.getAppId());
        snapshot.setEnvId(envId);
        snapshot.setDeploymentId(deploymentId);
        snapshot.setSnapshot(JsonUtils.objectToJsonNode(baseline));
        snapshotService.createOrUpdate(List.of(snapshot), List.of(
                DesignAppEnvSnapshot::getAppId,
                DesignAppEnvSnapshot::getEnvId,
                DesignAppEnvSnapshot::getDeploymentId
        ));
    }

    /**
     * Return the cached drift result for this env.
     * <p>
     * The expensive work (parallel RPC + row-level diff) runs in {@link #refreshDrift(Long)}
     * — fired automatically once after every successful deployment and from the manual
     * refresh endpoint; this method just reads the most recent cached record. Empty list
     * means "no drift detected" or "drift has never been checked".
     */
    @Override
    public List<ModelChangesDTO> compareDesignWithRuntime(Long envId) {
        Assert.notNull(envId, "envId must not be null");
        return findDriftByEnvId(envId)
                .map(DesignAppEnvDrift::getDriftContent)
                .<List<ModelChangesDTO>>map(node -> JsonUtils.jsonNodeToObject(node, new TypeReference<>() {}))
                .orElseGet(List::of);
    }

    /**
     * Issue a new Ed25519 keypair for the env.
     * <p>
     * Generation uses the JDK 25 {@code KeyPairGenerator}. Each runtime trusts
     * exactly one signer, so reissuing here is an atomic replacement — the
     * operator then swaps {@code system.runtime-public-key} on the
     * runtime side to match.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IssuedKey issueKey(Long envId) {
        DesignAppEnv env = this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        KeyPair keyPair = Ed25519Keys.generate();
        String encodedPublicKey = Ed25519Keys.encodePublicKey(keyPair.getPublic());
        String encodedPrivateKey = Ed25519Keys.encodePrivateKey(keyPair.getPrivate());

        env.setPublicKey(encodedPublicKey);
        env.setPrivateKey(encodedPrivateKey);
        this.updateOne(env);

        return new IssuedKey(encodedPublicKey);
    }

    /**
     * Overwrite design-time metadata with the current runtime state of this env.
     * <p>
     * Runs under the env deployment mutex so concurrent deploys and imports cannot
     * interleave. {@code useCached=false} forces a fresh {@link #refreshDrift(Long)}
     * inside the same transaction before applying; {@code useCached=true} trusts the
     * last cached drift record (which may be stale if the runtime changed since the
     * last check). No-op when the drift is empty, so it is safe to run against an
     * env that is already in sync.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDrift(Long envId, boolean useCached) {
        DesignAppEnv appEnv = this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        acquireEnvLock(appEnv);
        try {
            if (!useCached) {
                refreshDrift(envId);
            }
            List<ModelChangesDTO> drift = compareDesignWithRuntime(envId);
            if (drift.isEmpty()) {
                // Still mint a synthetic version only when we have changes to record; a no-op
                // import should not advance currentVersionId.
                return;
            }
            // Require a baseline snapshot to exist so the post-import snapshot is a legal
            // (appId, envId, deploymentId) row. For first-time import the baseline is an
            // empty map by convention — applyInvertedDrift + applyChangesToBaseline both
            // handle the empty case.
            Map<String, List<Map<String, Object>>> baseline = findLatestSnapshotByEnvId(envId)
                    .map(DesignAppEnvSnapshot::getSnapshot)
                    .<Map<String, List<Map<String, Object>>>>map(node -> JsonUtils.jsonNodeToObject(node, new TypeReference<>() {}))
                    .orElseGet(LinkedHashMap::new);

            applyInvertedDriftToDesign(drift);

            DesignAppVersion synthetic = createSyntheticVersion(appEnv);
            appEnv.setCurrentVersionId(synthetic.getId());
            this.updateOne(appEnv);

            writeImportedSnapshot(appEnv, synthetic.getId(), baseline, drift);
            clearDriftCache(appEnv);
        } finally {
            releaseEnvLock(envId);
        }
    }

    /**
     * Acquire the per-env mutex via compare-and-set on {@code envStatus}. The mutex is
     * shared with the deployment path, so this will refuse to proceed while a deploy is
     * in flight — import and deploy cannot be concurrent on the same env.
     */
    private void acquireEnvLock(DesignAppEnv appEnv) {
        Filters casFilter = new Filters()
                .eq(DesignAppEnv::getId, appEnv.getId())
                .eq(DesignAppEnv::getEnvStatus, DesignAppEnvStatus.STABLE);
        DesignAppEnv update = new DesignAppEnv();
        update.setEnvStatus(DesignAppEnvStatus.DEPLOYING);
        Integer affected = this.updateByFilter(casFilter, update);
        Assert.isTrue(affected != null && affected == 1,
                "Env {0} is currently DEPLOYING or missing — an import or deployment is in progress. Retry later.",
                appEnv.getId());
        appEnv.setEnvStatus(DesignAppEnvStatus.DEPLOYING);
    }

    private void releaseEnvLock(Long envId) {
        Filters filter = new Filters().eq(DesignAppEnv::getId, envId);
        DesignAppEnv update = new DesignAppEnv();
        update.setEnvStatus(DesignAppEnvStatus.STABLE);
        this.updateByFilter(filter, update);
    }

    /**
     * Apply the inverted drift to Design-side models.
     * <p>
     * The drift was computed as "operations to apply to runtime to match snapshot", so
     * importing runtime state into design-time flips every operation:
     * <ul>
     *   <li>{@code drift.deletedRows} (runtime has, snapshot doesn't) → CREATE on Design
     *       using {@code currentData} (the runtime values). Iteration order follows the
     *       declared parent→child sequence in {@link MetadataConstant#VERSION_CONTROL_MODELS}
     *       so FK references land after their targets.</li>
     *   <li>{@code drift.updatedRows} → UPDATE on Design with the runtime values that live
     *       in {@code dataBeforeChange}, plus the row id. Order is not load-bearing.</li>
     *   <li>{@code drift.createdRows} (snapshot has, runtime doesn't) → DELETE from Design
     *       by id. Deletion runs in reverse model order so children drop before parents,
     *       avoiding FK violations.</li>
     * </ul>
     */
    private void applyInvertedDriftToDesign(List<ModelChangesDTO> drift) {
        Map<String, ModelChangesDTO> byModel = drift.stream()
                .collect(Collectors.toMap(ModelChangesDTO::getModelName, m -> m));

        // Inserts: parent before child — follow the declared map order.
        for (String designModel : MetadataConstant.VERSION_CONTROL_MODELS.keySet()) {
            ModelChangesDTO changes = byModel.get(designModel);
            if (changes == null) {
                continue;
            }
            List<Map<String, Object>> toCreate = changes.getDeletedRows().stream()
                    .map(RowChangeDTO::getCurrentData)
                    .filter(Objects::nonNull)
                    .map(HashMap::new)
                    .collect(Collectors.toList());
            if (!toCreate.isEmpty()) {
                modelService.createList(designModel, Cast.of(toCreate));
            }
        }

        // Updates: order irrelevant.
        for (Map.Entry<String, ModelChangesDTO> entry : byModel.entrySet()) {
            String designModel = entry.getKey();
            List<Map<String, Object>> toUpdate = new ArrayList<>();
            for (RowChangeDTO row : entry.getValue().getUpdatedRows()) {
                Map<String, Object> payload = new HashMap<>();
                if (row.getDataBeforeChange() != null) {
                    payload.putAll(row.getDataBeforeChange());
                }
                payload.put(ModelConstant.ID, row.getRowId());
                toUpdate.add(payload);
            }
            if (!toUpdate.isEmpty()) {
                modelService.updateList(designModel, toUpdate);
            }
        }

        // Deletes: reverse the declared order so children drop before parents.
        List<String> reversed = new ArrayList<>(MetadataConstant.VERSION_CONTROL_MODELS.keySet());
        Collections.reverse(reversed);
        for (String designModel : reversed) {
            ModelChangesDTO changes = byModel.get(designModel);
            if (changes == null) {
                continue;
            }
            List<Serializable> ids = changes.getCreatedRows().stream()
                    .map(RowChangeDTO::getRowId)
                    .filter(Objects::nonNull)
                    .map(id -> (Serializable) id)
                    .toList();
            if (!ids.isEmpty()) {
                modelService.deleteByIds(designModel, Cast.of(ids));
            }
        }
    }

    /**
     * Mint a frozen, content-free {@link DesignAppVersion} that marks the env's
     * currentVersionId as "imported from runtime at <timestamp>". Naming the version by
     * the wall-clock timestamp is intentional: operators browsing version history can
     * tell at a glance that this entry did not come from the usual DRAFT→SEALED→FROZEN
     * workflow. {@code versionedContent} is empty because the design-time state was
     * overwritten wholesale rather than assembled from work items.
     */
    private DesignAppVersion createSyntheticVersion(DesignAppEnv appEnv) {
        LocalDateTime now = LocalDateTime.now();
        DesignAppVersion version = new DesignAppVersion();
        version.setAppId(appEnv.getAppId());
        version.setName("imported-from-runtime-" + now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        version.setStatus(DesignAppVersionStatus.FROZEN);
        version.setSealedTime(now);
        version.setFrozenTime(now);
        version.setVersionedContent(JsonUtils.objectToJsonNode(List.<ModelChangesDTO>of()));
        Long id = appVersionService.createOne(version);
        version.setId(id);
        return version;
    }

    /**
     * Persist the post-import snapshot keyed by the synthetic version id (which doubles
     * as the {@code deploymentId} column on the snapshot row — CosID keeps version and
     * deployment identifiers globally unique, so reusing the slot does not risk collision).
     * <p>
     * The snapshot equals {@code baseline} with the inverted drift applied, computed here
     * instead of re-reading the runtime so an imported env can be diff-checked later
     * without another round-trip.
     */
    private void writeImportedSnapshot(DesignAppEnv appEnv, Long syntheticVersionId,
                                       Map<String, List<Map<String, Object>>> baseline,
                                       List<ModelChangesDTO> drift) {
        Map<String, List<Map<String, Object>>> imported = new LinkedHashMap<>(baseline);
        applyInvertedDriftToSnapshot(imported, drift);

        DesignAppEnvSnapshot snapshot = new DesignAppEnvSnapshot();
        snapshot.setAppId(appEnv.getAppId());
        snapshot.setEnvId(appEnv.getId());
        snapshot.setDeploymentId(syntheticVersionId);
        snapshot.setSnapshot(JsonUtils.objectToJsonNode(imported));
        snapshotService.createOrUpdate(List.of(snapshot), List.of(
                DesignAppEnvSnapshot::getAppId,
                DesignAppEnvSnapshot::getEnvId,
                DesignAppEnvSnapshot::getDeploymentId
        ));
    }

    /**
     * Apply the inverted drift in the snapshot's own address space (model name → rows),
     * mirroring {@link #applyInvertedDriftToDesign} but against the in-memory map the
     * snapshot will serialize. Kept separate so the Design-side writes and the snapshot
     * write stay decoupled — if one is stubbed out in tests the other still verifies.
     */
    private void applyInvertedDriftToSnapshot(Map<String, List<Map<String, Object>>> baseline,
                                              List<ModelChangesDTO> drift) {
        for (ModelChangesDTO modelChanges : drift) {
            String designModel = modelChanges.getModelName();
            List<Map<String, Object>> baselineRows = baseline.computeIfAbsent(designModel, k -> new ArrayList<>());
            Map<Long, Map<String, Object>> baselineById = indexById(baselineRows);

            // deletedRows (runtime-only) → ensure present in snapshot with runtime data
            for (RowChangeDTO row : modelChanges.getDeletedRows()) {
                if (row.getCurrentData() != null) {
                    baselineById.put(extractRowId(row), new HashMap<>(row.getCurrentData()));
                }
            }
            // updatedRows → overwrite fields with runtime values (dataBeforeChange)
            for (RowChangeDTO row : modelChanges.getUpdatedRows()) {
                Map<String, Object> existing = baselineById.get(row.getRowId());
                if (existing == null) {
                    existing = new HashMap<>();
                    existing.put(ModelConstant.ID, row.getRowId());
                }
                if (row.getDataBeforeChange() != null) {
                    existing.putAll(row.getDataBeforeChange());
                }
                baselineById.put(row.getRowId(), existing);
            }
            // createdRows (snapshot-only) → drop from snapshot
            for (RowChangeDTO row : modelChanges.getCreatedRows()) {
                baselineById.remove(row.getRowId());
            }

            baseline.put(designModel, new ArrayList<>(baselineById.values()));
        }
    }

    /**
     * Zero the drift cache after a successful import: the design-time rows we just wrote
     * match the runtime we imported from, so there is no known drift. Keep
     * {@code checkStatus=SUCCESS} so the UI does not flag the env as "needs re-check".
     */
    private void clearDriftCache(DesignAppEnv appEnv) {
        upsertDriftRecord(appEnv, DesignDriftCheckStatus.SUCCESS, List.of(), null);
    }

    /**
     * Recompute the drift for this env and upsert the cached {@link DesignAppEnvDrift}
     * row. Runtime data is fetched in parallel across version-controlled models to keep
     * the overall latency close to the slowest single RPC rather than the sum.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshDrift(Long envId) {
        DesignAppEnv appEnv = this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        DesignAppEnvSnapshot snapshot = findLatestSnapshotByEnvId(envId).orElse(null);
        if (snapshot == null) {
            // No deployment has happened yet — nothing to diff against. Record this so the
            // UI can distinguish "never deployed" from "deployed, no drift".
            upsertDriftRecord(appEnv, DesignDriftCheckStatus.SUCCESS, List.of(),
                    "No snapshot — env has never been deployed.");
            return;
        }

        Map<String, List<Map<String, Object>>> snapshotData = JsonUtils.jsonNodeToObject(
                snapshot.getSnapshot(), new TypeReference<>() {});

        try {
            List<ModelChangesDTO> drift = computeDriftInParallel(appEnv, snapshotData);
            upsertDriftRecord(appEnv, DesignDriftCheckStatus.SUCCESS, drift, null);
        } catch (RuntimeException e) {
            log.warn("Drift check failed for env {}: {}", envId, e.getMessage());
            // Preserve the previous driftContent — we couldn't prove drift is gone,
            // and we don't want to silently "clear" it on a transient error.
            List<ModelChangesDTO> previous = compareDesignWithRuntime(envId);
            upsertDriftRecord(appEnv, DesignDriftCheckStatus.FAILURE, previous, e.getMessage());
        }
    }

    /**
     * Fan out the per-model runtime fetches concurrently via {@link CompletableFuture},
     * with every subtask dispatched onto a fresh virtual thread via
     * {@link #DRIFT_VIRTUAL_EXECUTOR}. This matters because remote metadata export is
     * I/O bound — on the common pool (platform threads capped by CPU count) the 11
     * version-controlled models would serialize into two or three rounds; on VTs they
     * all fire immediately and unmount their carrier during the blocking socket read.
     * <p>
     * Context propagation: the caller's {@link Context} is captured before forking and
     * re-bound inside each subtask via {@link ContextHolder#callWith}, so tenant / user
     * scoped values still resolve when {@code remoteApiClient} reads them downstream —
     * {@link java.lang.ScopedValue} does not auto-inherit into forked virtual threads,
     * so this binding is explicit on purpose.
     * <p>
     * Any individual failure surfaces as a {@link RuntimeException} to the caller via
     * unwrapping the {@link java.util.concurrent.CompletionException} thrown by
     * {@link CompletableFuture#join()}.
     */
    private List<ModelChangesDTO> computeDriftInParallel(DesignAppEnv appEnv,
                                                         Map<String, List<Map<String, Object>>> snapshotData) {
        Context capturedContext = ContextHolder.cloneContext();
        List<CompletableFuture<ModelChangesDTO>> futures = new ArrayList<>();
        for (Map.Entry<String, String> entry : MetadataConstant.VERSION_CONTROL_MODELS.entrySet()) {
            String designModel = entry.getKey();
            String runtimeModel = entry.getValue();
            List<Map<String, Object>> snapshotRows = snapshotData.getOrDefault(designModel, Collections.emptyList());
            futures.add(CompletableFuture.supplyAsync(() -> ContextHolder.callWith(capturedContext, () -> {
                List<Map<String, Object>> runtimeRows = fetchRuntimeData(runtimeModel, appEnv);
                return diffSnapshotVsRuntime(designModel, runtimeModel, snapshotRows, runtimeRows);
            }), DRIFT_VIRTUAL_EXECUTOR));
        }

        List<ModelChangesDTO> result = new ArrayList<>();
        // join() below throws CompletionException wrapping the underlying RuntimeException;
        // unwrap so the caller sees the original reason.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw ce;
        }
        for (CompletableFuture<ModelChangesDTO> future : futures) {
            ModelChangesDTO diff = future.join();
            if (diff != null) {
                result.add(diff);
            }
        }
        return result;
    }

    /**
     * Idempotent write of the drift cache keyed by {@code (appId, envId)}. Rematched by the
     * UNIQUE index on {@code design_app_env_drift(app_id, env_id)} at the DB level.
     */
    private void upsertDriftRecord(DesignAppEnv appEnv,
                                   DesignDriftCheckStatus status,
                                   List<ModelChangesDTO> drift,
                                   String errorMessage) {
        DesignAppEnvDrift record = new DesignAppEnvDrift();
        record.setAppId(appEnv.getAppId());
        record.setEnvId(appEnv.getId());
        record.setHasDrift(drift != null && !drift.isEmpty());
        record.setDriftContent(drift == null ? null : JsonUtils.objectToJsonNode(drift));
        record.setCheckStatus(status);
        record.setErrorMessage(truncate(errorMessage));
        record.setLastCheckedTime(LocalDateTime.now());
        driftService.createOrUpdate(List.of(record), List.of(
                DesignAppEnvDrift::getAppId,
                DesignAppEnvDrift::getEnvId
        ));
    }

    private Optional<DesignAppEnvDrift> findDriftByEnvId(Long envId) {
        Filters filters = new Filters().eq(DesignAppEnvDrift::getEnvId, envId);
        return driftService.searchOne(new FlexQuery(filters));
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MESSAGE_MAX_LENGTH ? text : text.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    // ======================== Snapshot building ========================

    /**
     * Apply merged deployment changes onto the baseline snapshot (in-place mutation).
     * <p>
     * For each model in {@code mergedChanges}:
     * <ul>
     *   <li>CREATE rows → add to baseline by id</li>
     *   <li>UPDATE rows → overwrite baseline row by id using {@code currentData}</li>
     *   <li>DELETE rows → remove from baseline by id</li>
     * </ul>
     *
     * @param baseline       mutable map: designModelName → list of row data
     * @param mergedChanges  the deployment's merged changes
     */
    private void applyChangesToBaseline(Map<String, List<Map<String, Object>>> baseline,
                                        List<ModelChangesDTO> mergedChanges) {
        if (mergedChanges == null) {
            return;
        }
        for (ModelChangesDTO modelChanges : mergedChanges) {
            String designModel = modelChanges.getModelName();
            List<Map<String, Object>> baselineRows = baseline.computeIfAbsent(designModel, k -> new ArrayList<>());
            Map<Long, Map<String, Object>> baselineById = indexById(baselineRows);

            for (RowChangeDTO created : modelChanges.getCreatedRows()) {
                upsertBaselineRow(baselineById, created);
            }

            for (RowChangeDTO updated : modelChanges.getUpdatedRows()) {
                upsertBaselineRow(baselineById, updated);
            }

            for (RowChangeDTO deleted : modelChanges.getDeletedRows()) {
                baselineById.remove(extractRowId(deleted));
            }

            baseline.put(designModel, new ArrayList<>(baselineById.values()));
        }
    }

    // ======================== Snapshot comparison ========================

    /**
     * Find the latest snapshot for an environment.
     * <p>
     * Since each deployment now writes its own snapshot row (unique on
     * {@code (appId, envId, deploymentId)}), this returns the most recent one — ordered
     * by id DESC which is time-sortable for snowflake / auto-increment IDs.
     */
    private Optional<DesignAppEnvSnapshot> findLatestSnapshotByEnvId(Long envId) {
        Filters filters = new Filters().eq(DesignAppEnvSnapshot::getEnvId, envId);
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(ModelConstant.ID));
        return snapshotService.searchOne(query);
    }

    /**
     * Fetch runtime metadata for a version-controlled model via the signed remote HTTP
     * path. Always remote — see the class javadoc for why the previous local shortcut
     * was removed.
     */
    private List<Map<String, Object>> fetchRuntimeData(String runtimeModel, DesignAppEnv appEnv) {
        return remoteApiClient.fetchRuntimeMetadata(appEnv, runtimeModel);
    }

    /**
     * Compute the diff between snapshot rows and runtime rows for a single model.
     * Uses primary id to match rows between snapshot and runtime.
     * <p>
     * <ul>
     *   <li>CREATE — snapshot row with no matching runtime row (missing in runtime)</li>
     *   <li>UPDATE — matched rows where business field values differ</li>
     *   <li>DELETE — runtime row with no matching snapshot row (extra in runtime)</li>
     * </ul>
     */
    private ModelChangesDTO diffSnapshotVsRuntime(String designModel, String runtimeModel,
                                                   List<Map<String, Object>> snapshotRows,
                                                   List<Map<String, Object>> runtimeRows) {
        Set<String> compareFields = getComparableFields(designModel, runtimeModel);

        Map<Long, Map<String, Object>> runtimeById = indexById(runtimeRows);
        Set<Long> matchedRuntimeIds = new HashSet<>();

        ModelChangesDTO modelChangesDTO = new ModelChangesDTO(designModel);

        for (Map<String, Object> snapshotRow : snapshotRows) {
            Long rowId = extractRowId(snapshotRow);
            Map<String, Object> runtimeRow = runtimeById.get(rowId);

            if (runtimeRow == null) {
                modelChangesDTO.addCreatedRow(toRowChangeDTO(designModel, AccessType.CREATE, snapshotRow));
            } else {
                matchedRuntimeIds.add(rowId);
                Map<String, Object> diffFields = compareFieldValues(snapshotRow, runtimeRow, compareFields);
                if (!diffFields.isEmpty()) {
                    RowChangeDTO rowChangeDTO = toRowChangeDTO(designModel, AccessType.UPDATE, snapshotRow);
                    rowChangeDTO.setDataBeforeChange(extractFields(runtimeRow, diffFields.keySet()));
                    rowChangeDTO.setDataAfterChange(extractFields(snapshotRow, diffFields.keySet()));
                    modelChangesDTO.addUpdatedRow(rowChangeDTO);
                }
            }
        }

        for (Map<String, Object> runtimeRow : runtimeRows) {
            Long rowId = extractRowId(runtimeRow);
            if (!matchedRuntimeIds.contains(rowId)) {
                modelChangesDTO.addDeletedRow(toRowChangeDTO(designModel, AccessType.DELETE, runtimeRow));
            }
        }

        if (modelChangesDTO.getCreatedRows().isEmpty() && modelChangesDTO.getUpdatedRows().isEmpty()
                && modelChangesDTO.getDeletedRows().isEmpty()) {
            return null;
        }
        return modelChangesDTO;
    }

    // ======================== Utility methods ========================

    /**
     * Get fields suitable for comparison: the intersection of design and runtime model fields,
     * excluding identity, audit, and system fields.
     * <p>
     * Kept as a protected instance method (not {@code static}) so tests can stub it via a
     * spy — {@code Mockito.mockStatic} is thread-local by default and does not reach the
     * {@link CompletableFuture} worker threads that call this during parallel drift checks.
     */
    protected Set<String> getComparableFields(String designModel, String runtimeModel) {
        Set<String> designFields = ModelManager.getModelFieldsWithoutXToMany(designModel);
        Set<String> runtimeFields = ModelManager.getModelFieldsWithoutXToMany(runtimeModel);
        Set<String> common = new HashSet<>(designFields);
        common.retainAll(runtimeFields);
        common.removeAll(ModelConstant.AUDIT_FIELDS);
        common.removeAll(Set.of(ModelConstant.ID, ModelConstant.EXTERNAL_ID, ModelConstant.VERSION));
        return common;
    }

    private static Map<Long, Map<String, Object>> indexById(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            index.put(extractRowId(row), new HashMap<>(row));
        }
        return index;
    }

    private static void upsertBaselineRow(Map<Long, Map<String, Object>> baselineById, RowChangeDTO rowChangeDTO) {
        Map<String, Object> currentData = rowChangeDTO.getCurrentData();
        if (currentData != null) {
            baselineById.put(extractRowId(rowChangeDTO), new HashMap<>(currentData));
        }
    }

    private static Long extractRowId(RowChangeDTO rowChangeDTO) {
        if (rowChangeDTO.getRowId() != null) {
            return rowChangeDTO.getRowId();
        }
        return extractRowId(rowChangeDTO.getCurrentData());
    }

    private static Long extractRowId(Map<String, Object> row) {
        Assert.notNull(row, "Snapshot row data cannot be null.");
        Object id = row.get(ModelConstant.ID);
        Assert.notNull(id, "Snapshot row id cannot be null. {0}", row);
        if (id instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(id));
    }

    private static Map<String, Object> compareFieldValues(Map<String, Object> snapshotRow,
                                                           Map<String, Object> runtimeRow,
                                                           Set<String> fields) {
        Map<String, Object> diffs = new HashMap<>();
        for (String field : fields) {
            Object snapshotVal = snapshotRow.get(field);
            Object runtimeVal = runtimeRow.get(field);
            if (!Objects.equals(snapshotVal, runtimeVal)) {
                diffs.put(field, runtimeVal);
            }
        }
        return diffs;
    }

    private static Map<String, Object> extractFields(Map<String, Object> row, Set<String> fieldNames) {
        Map<String, Object> result = new HashMap<>();
        for (String field : fieldNames) {
            result.put(field, row.get(field));
        }
        return result;
    }

    private static RowChangeDTO toRowChangeDTO(String modelName, AccessType accessType, Map<String, Object> row) {
        RowChangeDTO dto = new RowChangeDTO(modelName, extractRowId(row));
        dto.setAccessType(accessType);
        dto.setCurrentData(new HashMap<>(row));
        dto.setLastChangedById((Long) row.get(ModelConstant.UPDATED_ID));
        dto.setLastChangedBy((String) row.get(ModelConstant.UPDATED_BY));
        dto.setLastChangedTime(DateUtils.dateTimeToString(row.get(ModelConstant.UPDATED_TIME)));
        return dto;
    }


}
