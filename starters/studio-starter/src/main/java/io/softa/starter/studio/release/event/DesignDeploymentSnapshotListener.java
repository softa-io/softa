package io.softa.starter.studio.release.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * Handles deployment snapshot rebuilding after the deployment transaction commits.
 */
@Slf4j
@Component
public class DesignDeploymentSnapshotListener {

    private final DesignAppEnvService appEnvService;

    public DesignDeploymentSnapshotListener(DesignAppEnvService appEnvService) {
        this.appEnvService = appEnvService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeploymentCommitted(DesignDeploymentSnapshotEvent event) {
        try {
            appEnvService.takeSnapshot(event.envId(), event.deploymentId(), event.mergedChanges());
        } catch (RuntimeException e) {
            log.error("Failed to rebuild deployment snapshot, envId={}, deploymentId={}",
                    event.envId(), event.deploymentId(), e);
        }
    }
}
