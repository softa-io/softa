package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "upgrade callback")
@RestController
@RequestMapping("/upgrade")
public class UpgradeCallbackController extends EntityController<DesignDeploymentService, DesignDeployment, Long> {

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
