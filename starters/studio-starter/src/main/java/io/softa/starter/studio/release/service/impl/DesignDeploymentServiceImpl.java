package io.softa.starter.studio.release.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.studio.release.constant.ReleaseConstant;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.*;
import io.softa.starter.studio.release.enums.*;
import io.softa.starter.studio.release.event.AsyncDeploymentTriggerEvent;
import io.softa.starter.studio.release.event.DesignDeploymentSnapshotEvent;
import io.softa.starter.studio.release.service.*;
import io.softa.starter.studio.release.upgrade.DeploymentExecutor;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;
import io.softa.starter.studio.release.version.VersionDdl;
import io.softa.starter.studio.release.version.VersionDdlResult;
import io.softa.starter.studio.release.version.VersionMerger;

/**
 * DesignDeployment Model Service Implementation.
 * <p>
 * The Deployment is the single immutable deployment artifact. It combines content
 * preparation (released-version merging, DDL generation) and execution tracking
 * into one self-contained record.
 * <p>
 * Every deployment is async from the HTTP caller's perspective: {@link #deployToEnv}
 * returns the deployment id as soon as the record is committed, and the upgrade runs
 * on a virtual-thread listener. The listener posts a signed envelope to the target
 * runtime and waits for the webhook at {@link #handleUpgradeCallback} to report
 * completion — there is no "same-process" shortcut. The previous local-env heuristic
 * was dropped because env names collide at scale (many envs frequently share the
 * same identifier) so matching a Spring active profile to the target env was
 * unreliable and would occasionally apply an upgrade meant for a different env.
 * <p>
 * The env mutex ({@code envStatus: STABLE / DEPLOYING}) serialises deployments per
 * env. It is released by whichever code path completes the deployment — the
 * callback handler on success/failure, the async worker's failure branch if the
 * dispatch itself blows up, or {@link #cancelDeployment} for stuck records.
 */
@Slf4j
@Service
public class DesignDeploymentServiceImpl extends EntityServiceImpl<DesignDeployment, Long>
        implements DesignDeploymentService {

    private static final String CALLBACK_STATUS_SUCCESS = "SUCCESS";
    private static final String CALLBACK_STATUS_FAILURE = "FAILURE";

    @Autowired
    private DeploymentExecutor deploymentExecutor;

    @Autowired
    private RemoteApiClient remoteApiClient;

    @Lazy
    @Autowired
    private DesignAppEnvService appEnvService;

    @Lazy
    @Autowired
    private DesignAppVersionService appVersionService;

    @Autowired
    private DesignDeploymentVersionService deploymentVersionService;

    @Lazy
    @Autowired
    private DesignWorkItemService workItemService;

    @Autowired
    private DesignAppService appService;

    @Autowired
    private VersionDdl versionDdl;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * Deploy a validated Version to an Env.
     * <p>
     * Lifecycle (single path — the sync-vs-async split is gone):
     * <ol>
     *   <li>Acquires the per-env deployment mutex (envStatus: STABLE → DEPLOYING) via compare-and-set.</li>
     *   <li>Merges released version content and generates DDL.</li>
     *   <li>Creates a self-contained Deployment record with a fresh one-time
     *       callback token + expiry so a remote runtime can call us back.</li>
     *   <li>Publishes {@link AsyncDeploymentTriggerEvent}; the after-commit listener
     *       runs the upgrade on a virtual thread. Status transitions and mutex release
     *       happen inside that listener (or via {@link #handleUpgradeCallback} for the
     *       remote branch).</li>
     *   <li>On success: {@code env.currentVersionId} advances to {@code targetVersionId}.
     *       On failure or cancellation it does not advance.</li>
     * </ol>
     * <p>
     * If another deployment is in progress on the same env the CAS update affects 0 rows
     * and the caller gets an {@link IllegalArgumentException}. The UI / orchestrator is
     * expected to retry after the in-flight deployment finishes, or call
     * {@link #cancelDeployment(Long)} to force-release a stuck mutex.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long deployToEnv(DesignAppVersion targetVersion, DesignAppEnv targetEnv) {
        acquireDeploymentLock(targetEnv);
        Long sourceVersionId = targetEnv.getCurrentVersionId();
        PreparedDeploymentArtifact artifact = prepareDeploymentArtifact(targetEnv, targetVersion, sourceVersionId);
        DesignDeployment deployment = createDeploymentRecord(targetEnv, artifact);
        recordIncludedVersions(deployment.getId(), artifact.versionsForMerge());

        // Hand the upgrade off to AsyncDeploymentListener, which runs after this
        // transaction commits. Status + mutex are owned downstream by the runtime
        // callback handler. A pre-commit failure rolls back the mutex transition
        // automatically (single @Transactional scope).
        applicationEventPublisher.publishEvent(new AsyncDeploymentTriggerEvent(
                deployment.getId(), targetEnv.getId(), targetVersion.getId()));
        return deployment.getId();
    }

    /**
     * Acquire the per-env deployment mutex via a conditional update.
     * <p>
     * Transitions {@code envStatus} from {@code STABLE} to {@code DEPLOYING} atomically.
     * If zero rows match — either because another deployment holds the lock or the env
     * id no longer exists — an exception is thrown and the caller must retry later.
     * <p>
     * Also mutates the in-memory {@code targetEnv} so downstream logic sees the new
     * status without an extra read.
     */
    private void acquireDeploymentLock(DesignAppEnv targetEnv) {
        Filters casFilter = new Filters()
                .eq(DesignAppEnv::getId, targetEnv.getId())
                .eq(DesignAppEnv::getEnvStatus, DesignAppEnvStatus.STABLE);
        DesignAppEnv update = new DesignAppEnv();
        update.setEnvStatus(DesignAppEnvStatus.DEPLOYING);
        Integer affected = appEnvService.updateByFilter(casFilter, update);
        Assert.isTrue(affected != null && affected == 1,
                "Env {0} is currently Deploying or Importing. Retry later.",
                targetEnv.getId());
        targetEnv.setEnvStatus(DesignAppEnvStatus.DEPLOYING);
    }

    /**
     * Release the per-env deployment mutex. Executed unconditionally in a {@code finally}
     * block so failure paths also clear the lock.
     * <p>
     * Uses an unconditional update (no CAS) because only the current deployment owns the
     * lock at this point — acquisition is serialized upstream.
     */
    private void releaseDeploymentLock(Long envId) {
        Filters filter = new Filters().eq(DesignAppEnv::getId, envId);
        DesignAppEnv update = new DesignAppEnv();
        update.setEnvStatus(DesignAppEnvStatus.STABLE);
        appEnvService.updateByFilter(filter, update);
    }


    /**
     * Retry a failed deployment by creating a new Deployment with the same parameters.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long retryDeployment(Long deploymentId) {
        DesignDeployment failedDeployment = this.getById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException("Deployment does not exist! {0}", deploymentId));
        Assert.isEqual(failedDeployment.getDeployStatus(), DesignDeploymentStatus.FAILURE,
                "Only FAILURE deployments can be retried! Current status: {0}", failedDeployment.getDeployStatus());

        return appVersionService.deployToEnv(
                failedDeployment.getTargetVersionId(),
                failedDeployment.getEnvId());
    }

    /**
     * Cancel a stuck deployment and release its env mutex.
     * <p>
     * NO automatic DDL / data rollback is performed — see
     * {@link DesignDeploymentService#cancelDeployment}. This API is an operator
     * escape hatch for the case where an async deployment worker died before it
     * could update the deployment status and release the lock, leaving the env
     * pinned in {@code DEPLOYING} status.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelDeployment(Long deploymentId) {
        DesignDeployment deployment = this.getById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException("Deployment does not exist! {0}", deploymentId));

        DesignDeploymentStatus current = deployment.getDeployStatus();
        Assert.isTrue(current == DesignDeploymentStatus.PENDING || current == DesignDeploymentStatus.DEPLOYING,
                "Only PENDING / DEPLOYING deployments can be cancelled. Current status: {0}", current);

        // Mark rolled back on the record so history reflects the cancellation. The
        // attached message explicitly documents that no auto-rollback happened.
        deployment.setDeployStatus(DesignDeploymentStatus.ROLLED_BACK);
        deployment.setFinishedTime(LocalDateTime.now());
        String cancelNote = "Cancelled by operator at " + LocalDateTime.now()
                + "; no automatic data/DDL rollback performed — operators must manually revert"
                + " runtime state if partial changes were committed before cancellation.";
        String prevError = deployment.getErrorMessage();
        deployment.setErrorMessage(prevError == null || prevError.isBlank()
                ? cancelNote : prevError + " | " + cancelNote);
        this.updateOne(deployment);

        // Flip the env back to STABLE so new deployments can proceed.
        // env.currentVersionId is intentionally NOT modified here — it still points
        // at whatever version last completed successfully.
        releaseDeploymentLock(deployment.getEnvId());
    }

    /**
     * Async-path executor. Invoked by {@code AsyncDeploymentListener} on a virtual
     * thread after the deploy transaction commits.
     * <p>
     * NOT annotated {@code @Transactional}: each inner status transition (DEPLOYING
     * → SUCCESS / FAILURE) must commit eagerly so polling clients can see progress,
     * and so an upgrade that applied real changes on the remote runtime leaves a
     * durable audit trail even if a later step throws.
     * <p>
     * Mutex handling: a successful dispatch returns with the deployment still in
     * {@code DEPLOYING} and the env lock held — the remote runtime's webhook at
     * {@link #handleUpgradeCallback} releases the lock when completion arrives.
     * A failed dispatch falls through to {@link #dispatchRemoteDeployment}'s
     * failure branch which marks the deployment FAILURE and releases the lock
     * immediately so follow-up deployments are not blocked.
     * <p>
     * Crash safety is bounded by the worker staying alive: if the JVM dies
     * mid-flight the lock is left held and {@link #cancelDeployment(Long)} must
     * be called manually.
     */
    @Override
    public void executeAsyncDeployment(Long deploymentId, Long envId, Long targetVersionId) {
        DesignDeployment deployment;
        DesignAppEnv targetEnv;
        try {
            deployment = this.getById(deploymentId)
                    .orElseThrow(() -> new IllegalArgumentException("Deployment does not exist! {0}", deploymentId));
            targetEnv = appEnvService.getById(envId)
                    .orElseThrow(() -> new IllegalArgumentException("Env does not exist! {0}", envId));
        } catch (RuntimeException e) {
            log.error("Async deployment reload failed: deploymentId={}, envId={}", deploymentId, envId, e);
            releaseDeploymentLock(envId);
            return;
        }

        deployment.setDeployStatus(DesignDeploymentStatus.DEPLOYING);
        this.updateOne(deployment);
        dispatchRemoteDeployment(deployment, targetEnv);
    }

    @Override
    public void handleUpgradeCallback(String callbackToken, MetadataUpgradeCallback payload) {
        Assert.notBlank(callbackToken, "Callback token is required.");
        Assert.notNull(payload, "Callback payload is required.");

        DesignDeployment deployment = findDeploymentByCallbackToken(callbackToken);
        Assert.notNull(deployment, "Unknown callback token — no pending deployment matches.");
        Assert.isEqual(deployment.getDeployStatus(), DesignDeploymentStatus.DEPLOYING,
                "Callback rejected: deployment {0} is not in DEPLOYING state (current: {1}).",
                deployment.getId(), deployment.getDeployStatus());
        Assert.isTrue(deployment.getCallbackReceivedAt() == null,
                "Callback rejected: deployment {0} already received a completion webhook at {1}.",
                deployment.getId(), deployment.getCallbackReceivedAt());
        Assert.isTrue(deployment.getCallbackTokenExpireAt() == null
                        || deployment.getCallbackTokenExpireAt().isAfter(LocalDateTime.now()),
                "Callback rejected: token for deployment {0} expired at {1}.",
                deployment.getId(), deployment.getCallbackTokenExpireAt());

        DesignAppEnv targetEnv = appEnvService.getById(deployment.getEnvId())
                .orElseThrow(() -> new IllegalArgumentException("Env does not exist! {0}", deployment.getEnvId()));

        deployment.setCallbackReceivedAt(LocalDateTime.now());

        if (CALLBACK_STATUS_SUCCESS.equalsIgnoreCase(payload.getStatus())) {
            markDeploymentSuccess(deployment, targetEnv, deployment.getTargetVersionId(),
                    payload.getDurationMillis());
        } else {
            markDeploymentFailure(deployment, payload.getErrorMessage(), payload.getDurationMillis());
        }
        releaseDeploymentLock(targetEnv.getId());
    }

    // ======================== Private methods ========================

    /**
     * Dispatch the signed upgrade envelope to the target runtime. Success leaves the
     * deployment in {@code DEPLOYING} with the env mutex held — the runtime will POST
     * completion back to the studio webhook, which releases the lock. Dispatch
     * failure (network error, HTTP error from runtime) is treated as deployment
     * failure and the lock is released immediately so follow-up deployments are not
     * blocked.
     */
    private void dispatchRemoteDeployment(DesignDeployment deployment, DesignAppEnv targetEnv) {
        String callbackUrl = buildCallbackUrl();
        try {
            List<MetadataUpgradePackage> upgradePackages = deploymentExecutor.convertToUpgradePackages(
                    deserializeMergedChanges(deployment));
            remoteApiClient.remoteUpgrade(targetEnv, upgradePackages, callbackUrl, deployment.getCallbackToken());
            log.info("Remote upgrade dispatched: deploymentId={}, envId={}, callbackUrl={}",
                    deployment.getId(), targetEnv.getId(), callbackUrl);
            // Deliberately do NOT release the lock or transition status — the webhook
            // at handleUpgradeCallback does both once the runtime finishes.
        } catch (RuntimeException e) {
            log.error("Remote upgrade dispatch failed: deploymentId={}, envId={}",
                    deployment.getId(), targetEnv.getId(), e);
            markDeploymentFailure(deployment, "Dispatch failed: " + e.getMessage(), null);
            releaseDeploymentLock(targetEnv.getId());
        }
    }

    private void markDeploymentSuccess(DesignDeployment deployment, DesignAppEnv targetEnv,
                                       Long targetVersionId, Long durationMillis) {
        deployment.setDeployStatus(DesignDeploymentStatus.SUCCESS);
        if (durationMillis != null) {
            deployment.setDeployDuration(durationMillis / 1000.0);
        }
        deployment.setFinishedTime(LocalDateTime.now());
        this.updateOne(deployment);

        targetEnv.setCurrentVersionId(targetVersionId);
        appEnvService.updateOne(targetEnv);

        if (targetEnv.getEnvType() == DesignAppEnvType.PROD) {
            this.closeReleasedWorkItems(deployment);
            this.freezeTargetVersionIfNeeded(targetVersionId);
        }

        List<ModelChangesDTO> mergedChanges = deserializeMergedChanges(deployment);
        applicationEventPublisher.publishEvent(
                new DesignDeploymentSnapshotEvent(targetEnv.getId(), deployment.getId(), List.copyOf(mergedChanges)));
    }

    private void markDeploymentFailure(DesignDeployment deployment, String errorMessage, Long durationMillis) {
        try {
            deployment.setDeployStatus(DesignDeploymentStatus.FAILURE);
            if (durationMillis != null) {
                deployment.setDeployDuration(durationMillis / 1000.0);
            }
            deployment.setFinishedTime(LocalDateTime.now());
            deployment.setErrorMessage(errorMessage);
            this.updateOne(deployment);
        } catch (RuntimeException updateException) {
            log.error("Failed to persist FAILURE status on deployment {}", deployment.getId(), updateException);
        }
    }

    private List<ModelChangesDTO> deserializeMergedChanges(DesignDeployment deployment) {
        return JsonUtils.jsonNodeToObject(deployment.getMergedContent(), new TypeReference<>() {});
    }

    private DesignDeployment findDeploymentByCallbackToken(String callbackToken) {
        Filters filter = new Filters().eq(DesignDeployment::getCallbackToken, callbackToken);
        List<DesignDeployment> matches = this.searchList(new FlexQuery(filter));
        if (matches.isEmpty()) {
            return null;
        }
        Assert.isTrue(matches.size() == 1,
                "Multiple deployments match callback token — data integrity violated.");
        return matches.getFirst();
    }

    private String buildCallbackUrl() {
        String base = SystemConfig.env.getApiRootUrl();
        Assert.isTrue(StringUtils.hasText(base),
                "system.public-access-url must be set before remote deployments can dispatch.");
        return UriComponentsBuilder.fromUriString(base)
                .path(ReleaseConstant.CALLBACK_PATH)
                .build().toUriString();
    }

    private void closeReleasedWorkItems(DesignDeployment deployment) {
        Filters versionFilters = new Filters().eq(DesignDeploymentVersion::getDeploymentId, deployment.getId());
        List<DesignDeploymentVersion> deploymentVersions = deploymentVersionService.searchList(new FlexQuery(versionFilters));
        if (deploymentVersions.isEmpty()) {
            return;
        }

        List<Long> versionIds = deploymentVersions.stream()
                .map(DesignDeploymentVersion::getVersionId)
                .distinct()
                .toList();
        Filters workItemFilters = new Filters().in(DesignWorkItem::getVersionId, versionIds);
        List<DesignWorkItem> workItems = workItemService.searchList(new FlexQuery(workItemFilters));
        if (workItems.isEmpty()) {
            return;
        }

        LocalDateTime closedTime = deployment.getFinishedTime() != null ? deployment.getFinishedTime() : LocalDateTime.now();
        for (DesignWorkItem workItem : workItems) {
            if (workItem.getStatus() == DesignWorkItemStatus.CLOSED && workItem.getClosedTime() != null) {
                continue;
            }
            if (workItem.getStatus() != DesignWorkItemStatus.DONE
                    && workItem.getStatus() != DesignWorkItemStatus.CLOSED) {
                continue;
            }
            workItem.setStatus(DesignWorkItemStatus.CLOSED);
            workItem.setClosedTime(closedTime);
            workItemService.updateOne(workItem);
        }
    }

    /**
     * Auto-freeze the deployed target version.
     */
    private void freezeTargetVersionIfNeeded(Long targetVersionId) {
        DesignAppVersion targetVersion = appVersionService.getById(targetVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", targetVersionId));
        if (targetVersion.getStatus() == DesignAppVersionStatus.FROZEN) {
            return;
        }
        if (targetVersion.getStatus() == DesignAppVersionStatus.SEALED) {
            appVersionService.freezeVersion(targetVersionId);
        }
    }


    /**
     * Deserialize each version's versionedContent and merge them using {@link VersionMerger}.
     */
    private List<ModelChangesDTO> mergeVersionContents(List<DesignAppVersion> orderedVersions) {
        List<List<ModelChangesDTO>> allVersionChanges = new ArrayList<>();
        for (DesignAppVersion version : orderedVersions) {
            if (version.getVersionedContent() != null) {
                List<ModelChangesDTO> versionChanges = JsonUtils.jsonNodeToObject(
                        version.getVersionedContent(), new TypeReference<>() {});
                allVersionChanges.add(versionChanges);
            }
        }
        return VersionMerger.merge(allVersionChanges);
    }

    private PreparedDeploymentArtifact prepareDeploymentArtifact(
            DesignAppEnv targetEnv, DesignAppVersion targetVersion, Long sourceVersionId) {
        List<DesignAppVersion> versionsForMerge = appVersionService.getVersionsForMerge(sourceVersionId, targetVersion.getId());
        List<ModelChangesDTO> mergedChanges = mergeVersionContents(versionsForMerge);
        DatabaseType databaseType = appService.getFieldValue(targetEnv.getAppId(), DesignApp::getDatabaseType);
        VersionDdlResult ddlResult = versionDdl.generateDdlResult(databaseType, mergedChanges);
        String contentJson = JsonUtils.objectToString(mergedChanges);
        String diffHash = EncryptUtils.computeSha256(contentJson);
        String deployName = String.format("%s → %s (%s)",
                sourceVersionId != null ? "v" + sourceVersionId : "initial",
                targetVersion.getName(),
                targetEnv.getName());
        return new PreparedDeploymentArtifact(
                sourceVersionId,
                targetVersion.getId(),
                deployName,
                mergedChanges,
                ddlResult,
                diffHash,
                versionsForMerge
        );
    }

    private DesignDeployment createDeploymentRecord(DesignAppEnv targetEnv, PreparedDeploymentArtifact artifact) {
        DesignDeployment deployment = new DesignDeployment();
        deployment.setAppId(targetEnv.getAppId());
        deployment.setEnvId(targetEnv.getId());
        deployment.setSourceVersionId(artifact.sourceVersionId());
        deployment.setTargetVersionId(artifact.targetVersionId());
        deployment.setName(artifact.deployName());
        deployment.setDeployStatus(DesignDeploymentStatus.PENDING);
        deployment.setDiffHash(artifact.diffHash());
        deployment.setMergedContent(JsonUtils.objectToJsonNode(artifact.mergedChanges()));
        deployment.setMergedDdlTable(artifact.ddlResult().tableDdl());
        deployment.setMergedDdlIndex(artifact.ddlResult().indexDdl());
        deployment.setStartedTime(LocalDateTime.now());
        // Remote dispatch needs this one-time token to round-trip via the runtime webhook.
        deployment.setCallbackToken(UUID.randomUUID().toString().replace("-", ""));
        deployment.setCallbackTokenExpireAt(LocalDateTime.now().plus(ReleaseConstant.CALLBACK_TOKEN_TTL));
        if (ContextHolder.getContext().getUserId() != null) {
            deployment.setOperatorId(String.valueOf(ContextHolder.getContext().getUserId()));
        }
        return this.createOneAndFetch(deployment);
    }

    private void recordIncludedVersions(Long deploymentId, List<DesignAppVersion> versionsForMerge) {
        int sequence = 1;
        for (DesignAppVersion version : versionsForMerge) {
            DesignDeploymentVersion dv = new DesignDeploymentVersion();
            dv.setDeploymentId(deploymentId);
            dv.setVersionId(version.getId());
            dv.setSequence(sequence++);
            deploymentVersionService.createOne(dv);
        }
    }

    private record PreparedDeploymentArtifact(
            Long sourceVersionId,
            Long targetVersionId,
            String deployName,
            List<ModelChangesDTO> mergedChanges,
            VersionDdlResult ddlResult,
            String diffHash,
            List<DesignAppVersion> versionsForMerge
    ) {}

}
