package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.entity.DesignDeployment;
import io.softa.starter.studio.release.service.DesignDeploymentService;

/**
 * DesignDeployment Model Controller.
 * <p>
 * Deployment resource controller. Exposes deployment-centric operations such as retry and cancel.
 */
@Tag(name = "DesignDeployment")
@RestController
@RequestMapping("/DesignDeployment")
public class DesignDeploymentController extends EntityController<DesignDeploymentService, DesignDeployment, Long> {

    /**
     * Retry a failed deployment.
     */
    @Operation(description = "Retry a failed deployment by creating a new Deployment with the same parameters.")
    @PostMapping(value = "/retry")
    @Parameter(name = "id", description = "Deployment ID")
    public ApiResponse<Long> retry(@RequestParam Long id) {
        return ApiResponse.success(service.retryDeployment(id));
    }

    /**
     * Cancel a stuck (PENDING / DEPLOYING) deployment and release the env mutex.
     * <p>
     * <b>No automatic rollback is performed.</b> Runtime DDL / data changes already
     * applied by the upgrade stay applied; this endpoint only marks the deployment
     * record as {@code ROLLED_BACK} and frees the env so new deployments can proceed.
     * Operators must manually revert runtime state when partial changes were
     * committed before cancellation.
     */
    @Operation(description = "Cancel a stuck deployment (PENDING / DEPLOYING) and release the env mutex. "
            + "No automatic rollback — runtime changes already applied stay applied.")
    @PostMapping(value = "/cancel")
    @Parameter(name = "id", description = "Deployment ID")
    public ApiResponse<Void> cancel(@RequestParam Long id) {
        service.cancelDeployment(id);
        return ApiResponse.success();
    }

    /**
     * Refresh the status of a deployment that is stuck in {@code DEPLOYING} by
     * pulling the persisted outcome from the remote runtime. Used when the push
     * callback was lost (network blip, studio restart, runtime crash before send).
     * <p>
     * The runtime's {@code MetadataUpgradeHistory} table is the source of truth.
     * This endpoint converges the studio-side deployment to that state without
     * re-applying the upgrade. If the runtime is still in {@code RUNNING} or has
     * no record for the deployment's callback token, the call fails with a clear
     * message and the operator can either wait or use {@link #cancel}.
     */
    @Operation(description = "Refresh a stuck DEPLOYING deployment by pulling the persisted outcome "
            + "from the remote runtime. Used when the push callback was lost.")
    @PostMapping(value = "/refreshStatus")
    @Parameter(name = "id", description = "Deployment ID")
    public ApiResponse<Void> refreshStatus(@RequestParam Long id) {
        service.refreshDeploymentStatus(id);
        return ApiResponse.success();
    }

}
