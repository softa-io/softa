package io.softa.starter.studio.release.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * Handles deployment snapshot rebuilding after the deployment transaction commits,
 * then fires a one-shot drift recompute so the cached drift record reflects the
 * freshly-written snapshot (previously done by a 10-minute cron, now strictly
 * post-deploy + on manual operator request).
 */
@Slf4j
@Component
public class DesignDeploymentSnapshotListener {

    private final DesignAppEnvService appEnvService;
    private final ApplicationEventPublisher applicationEventPublisher;

    public DesignDeploymentSnapshotListener(DesignAppEnvService appEnvService,
                                            ApplicationEventPublisher applicationEventPublisher) {
        this.appEnvService = appEnvService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeploymentCommitted(DesignDeploymentSnapshotEvent event) {
        try {
            appEnvService.takeSnapshot(event.envId(), event.deploymentId(), event.mergedChanges());
        } catch (RuntimeException e) {
            log.error("Failed to rebuild deployment snapshot, envId={}, deploymentId={}",
                    event.envId(), event.deploymentId(), e);
            // Don't fire drift — it would compare new runtime against stale snapshot.
            return;
        }
        // Snapshot row is committed; schedule a drift recompute on a separate @Async
        // worker so this listener returns promptly even when the per-model export
        // fan-out takes seconds.
        applicationEventPublisher.publishEvent(new DesignAppEnvDriftRefreshEvent(event.envId()));
    }
}
