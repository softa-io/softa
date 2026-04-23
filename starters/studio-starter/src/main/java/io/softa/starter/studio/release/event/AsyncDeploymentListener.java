package io.softa.starter.studio.release.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.starter.studio.release.service.DesignDeploymentService;

/**
 * Dispatches async deployments onto a virtual-thread {@code @Async} pool
 * after the triggering transaction commits.
 * <p>
 * {@code @TransactionalEventListener(AFTER_COMMIT)} guarantees the deployment record
 * and env-lock writes are visible by the time the listener fires; {@code @Async}
 * (wired via {@code AsyncConfig} to {@code Executors.newVirtualThreadPerTaskExecutor()})
 * then decouples the HTTP caller from the actual upgrade work — the caller already
 * returned with the deployment id and polls for status.
 * <p>
 * Failure handling is delegated to {@link DesignDeploymentService#executeAsyncDeployment}:
 * the service method captures dispatch failures as {@code FAILURE} status on the
 * deployment and releases the env lock immediately. Successful dispatches leave the
 * lock held until the runtime webhook {@code handleUpgradeCallback} reports completion.
 * We only log here; throwing from an {@code @TransactionalEventListener} would be
 * swallowed by Spring and hide nothing more than we already log.
 */
@Slf4j
@Component
public class AsyncDeploymentListener {

    @Lazy
    @Autowired
    private DesignDeploymentService deploymentService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAsyncDeploymentTriggered(AsyncDeploymentTriggerEvent event) {
        try {
            deploymentService.executeAsyncDeployment(
                    event.deploymentId(), event.envId(), event.targetVersionId());
        } catch (RuntimeException e) {
            // executeAsyncDeployment owns its own catch + finally, so reaching here
            // means something unexpected bubbled past it — log loudly and move on.
            log.error("Unhandled async deployment failure: deploymentId={}, envId={}",
                    event.deploymentId(), event.envId(), e);
        }
    }
}
