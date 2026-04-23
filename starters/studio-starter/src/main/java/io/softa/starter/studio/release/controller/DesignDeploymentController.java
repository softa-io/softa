package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.signature.SignatureConstant;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;
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
     * Runtime → studio webhook for async upgrade completion.
     * <p>
     * The runtime echoes the one-time {@code X-Softa-Callback-Token} from the originating
     * deployment envelope. The service matches it to a pending deployment, burns it on
     * first receipt, and applies the success/failure state. No Ed25519 signature is
     * required on this direction — the token was generated server-side, only transmitted
     * over the signed outbound request, and is single-use.
     * <p>
     * Returns {@code 200 OK} so idempotent retries from the runtime are well-behaved.
     * Validation failures surface as {@code 4xx} via the service layer's assertions.
     */
    @Operation(description = "Webhook endpoint — the runtime POSTs here with the SUCCESS / FAILURE payload"
            + " once an async upgrade completes. The token in X-Softa-Callback-Token must match"
            + " the pending deployment that was dispatched.")
    @PostMapping(value = "/callback")
    public ResponseEntity<ApiResponse<Void>> callback(
            @RequestHeader(SignatureConstant.CALLBACK_TOKEN) String callbackToken,
            @RequestBody MetadataUpgradeCallback payload) {
        service.handleUpgradeCallback(callbackToken, payload);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success());
    }

}
