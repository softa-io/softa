package io.softa.starter.metadata.upgrade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;
import io.softa.starter.metadata.dto.MetadataUpgradeRequest;
import io.softa.starter.metadata.service.MetadataService;

/**
 * Runs the metadata upgrade applied by {@link MetadataService#upgradeMetadata} on a
 * background virtual thread and delivers the outcome to the studio via
 * {@link MetadataCallbackClient}.
 * <p>
 * The upgrade endpoint returns 202 as soon as the envelope is validated; this worker
 * is the entire execution path from that point on. Because @Async proxies require the
 * caller to be outside the bean, the {@link io.softa.starter.metadata.controller.MetadataController}
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataUpgradeWorker {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILURE = "FAILURE";

    private final MetadataService metadataService;
    private final MetadataCallbackClient callbackClient;

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
        long startNanos = System.nanoTime();
        MetadataUpgradeCallback callback = new MetadataUpgradeCallback();
        try {
            metadataService.upgradeMetadata(request.getPackages());
            metadataService.reloadMetadata();
            callback.setStatus(STATUS_SUCCESS);
        } catch (RuntimeException e) {
            log.error("Metadata upgrade failed for callback={}", request.getCallbackUrl(), e);
            callback.setStatus(STATUS_FAILURE);
            callback.setErrorMessage(e.getMessage());
        } finally {
            callback.setDurationMillis((System.nanoTime() - startNanos) / 1_000_000L);
            callbackClient.sendCallback(request.getCallbackUrl(), request.getCallbackToken(), callback);
        }
    }
}
