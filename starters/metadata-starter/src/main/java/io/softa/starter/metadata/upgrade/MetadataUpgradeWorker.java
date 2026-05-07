package io.softa.starter.metadata.upgrade;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.metadata.dto.MetadataUpgradeRequest;
import io.softa.starter.metadata.service.MetadataService;
import io.softa.starter.metadata.service.MetadataUpgradeHistoryService;

/**
 * Runs the metadata upgrade applied by {@link MetadataService#upgradeMetadata} on a
 * background virtual thread. Outcomes are persisted to {@code MetadataUpgradeHistory}
 * (the source of truth) and best-effort delivered to the studio via
 * {@link MetadataCallbackClient}.
 * <p>
 * The upgrade endpoint returns 202 as soon as the envelope is validated; this worker
 * is the entire execution path from that point on. Because @Async proxies require the
 * caller to be outside the bean, the {@link io.softa.starter.metadata.controller.UpgradeController}
 * is a separate bean and calls {@link #runUpgrade(MetadataUpgradeRequest)} through the
 * proxy rather than a same-class method.
 * <p>
 * Status semantics:
 * <ul>
 *   <li>{@code SUCCESS}: packages applied AND in-memory metadata reload succeeded.</li>
 *   <li>{@code FAILURE}: either step threw; the first exception's message is carried in
 *       {@code errorMessage} and the partial apply (if any) is left in place — we do not
 *       attempt a DDL/data rollback here, the studio is expected to surface the failure
 *       for operator action.</li>
 * </ul>
 * <p>
 * Persistence vs callback: the history row is written <b>before</b> the callback is
 * sent. If the callback fails (network blip, studio restart) the studio can still
 * recover the outcome via {@code GET /upgrade/runtime/upgradeStatus}. The push
 * callback is a latency optimisation, not the durable contract.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataUpgradeWorker {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILURE = "FAILURE";

    private final MetadataService metadataService;
    private final MetadataCallbackClient callbackClient;
    private final MetadataUpgradeHistoryService historyService;

    /**
     * Apply the upgrade and dispatch a webhook back to the studio.
     * <p>
     * {@code @SwitchUser(INTEGRATION_USER)} replaces the per-HTTP-request switch that
     * previously lived on the controller — the async boundary severs the thread from
     * the request context, so the switch must apply to this method, not the caller.
     */
    @Async
    @SwitchUser(value = SystemUser.INTEGRATION_USER)
    public void runUpgrade(MetadataUpgradeRequest request) {
        String token = request.getCallbackToken();
        long startNanos = System.nanoTime();

        boolean firstAttempt = historyService.markRunning(token, request.getEnvId(),
                summarise(request.getPackages()));
        if (!firstAttempt) {
            // The studio retried the dispatch (e.g. the original POST timed out client-side
            // after the runtime had already accepted it). The first attempt's outcome is
            // canonical — re-running the upgrade would double-apply changes. Best-effort
            // replay the callback so the studio converges anyway.
            log.warn("Duplicate upgrade dispatch for token={}, skipping; replaying last known callback", token);
            replayCallback(request, token);
            return;
        }

        MetadataUpgradeCallback callback = new MetadataUpgradeCallback();
        boolean upgradeCommitted = false;
        try {
            metadataService.upgradeMetadata(request.getPackages());
            upgradeCommitted = true;
            double durationSeconds = elapsedSeconds(startNanos);
            historyService.markSuccess(token, durationSeconds);
            callback.setStatus(STATUS_SUCCESS);
            callback.setDurationTime(durationSeconds);
        } catch (RuntimeException e) {
            double durationSeconds = elapsedSeconds(startNanos);
            historyService.markFailure(token, e.getMessage(), durationSeconds);
            log.error("Metadata upgrade failed for callback={}", request.getCallbackUrl(), e);
            callback.setStatus(STATUS_FAILURE);
            callback.setErrorMessage(e.getMessage());
            callback.setDurationTime(durationSeconds);
        } finally {
            // reloadMetadata is best-effort and must not flip the upgrade outcome:
            // the data is already committed, so a broadcast failure here would only
            // mean replicas serve stale metadata until a later reload trigger lands.
            // We log + persist a warning on the SUCCESS history row instead of
            // recording FAILURE, which would prevent currentVersionId from advancing
            // and cause the next deploy to retry an already-applied change.
            if (upgradeCommitted) {
                try {
                    metadataService.reloadMetadata();
                } catch (RuntimeException reloadFailure) {
                    log.error("Metadata committed but reload broadcast failed for token={}; "
                                    + "replicas may serve stale metadata until next reload trigger",
                            token, reloadFailure);
                    try {
                        historyService.recordReloadWarning(token, reloadFailure.getMessage());
                    } catch (RuntimeException recordWarningFailure) {
                        log.error("Failed to persist reload warning on history for token={}",
                                token, recordWarningFailure);
                    }
                }
            }
            callbackClient.sendCallback(request.getCallbackUrl(), token, callback);
        }
    }

    /**
     * Re-emit the callback for an already-completed upgrade. Reads the canonical state
     * from the history row so a duplicate dispatch converges the studio without a
     * second apply.
     */
    private void replayCallback(MetadataUpgradeRequest request, String token) {
        var history = historyService.findByToken(token);
        if (history == null || history.getStatus() == null) {
            log.warn("Cannot replay callback for token={} — history row vanished", token);
            return;
        }
        MetadataUpgradeCallback callback = new MetadataUpgradeCallback();
        switch (history.getStatus()) {
            case SUCCESS -> callback.setStatus(STATUS_SUCCESS);
            case FAILURE -> {
                callback.setStatus(STATUS_FAILURE);
                callback.setErrorMessage(history.getErrorMessage());
            }
            case RUNNING -> {
                // The first attempt is still in flight on a sibling worker — let it
                // finish and send the canonical callback. Skipping is correct.
                log.info("Replay skipped — original upgrade for token={} is still RUNNING", token);
                return;
            }
        }
        if (history.getDurationTime() != null) {
            callback.setDurationTime(history.getDurationTime());
        }
        callbackClient.sendCallback(request.getCallbackUrl(), token, callback);
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    /**
     * Compact JSON summary of the packages, kept for audit on the history row.
     * Stores per-model row counts only — the actual payload lives in the request log.
     */
    private static JsonNode summarise(List<MetadataUpgradePackage> packages) {
        if (packages == null || packages.isEmpty()) {
            return JsonUtils.objectToJsonNode(List.of());
        }
        List<PackageSummary> summary = new ArrayList<>(packages.size());
        for (MetadataUpgradePackage pkg : packages) {
            summary.add(new PackageSummary(
                    pkg.getModelName(),
                    pkg.getCreateRows() == null ? 0 : pkg.getCreateRows().size(),
                    pkg.getUpdateRows() == null ? 0 : pkg.getUpdateRows().size(),
                    pkg.getDeleteIds() == null ? 0 : pkg.getDeleteIds().size()));
        }
        return JsonUtils.objectToJsonNode(summary);
    }

    private record PackageSummary(String modelName, int createCount, int updateCount, int deleteCount) {
    }
}
