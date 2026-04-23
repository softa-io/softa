package io.softa.starter.metadata.controller;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.signature.RequireSignature;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.dto.MetadataUpgradeRequest;
import io.softa.starter.metadata.service.MetadataService;
import io.softa.starter.metadata.upgrade.MetadataUpgradeWorker;

/**
 * Metadata Upgrade controller.
 * <p>
 * The {@code /metadata/upgrade} and {@code /metadata/exportRuntimeMetadata} endpoints
 * are tagged with {@link RequireSignature} — they are studio-internal APIs called
 * over the public network, so every invocation must carry a valid Ed25519 signature
 * issued by the calling studio env. Unsigned or stale-nonce requests are rejected at
 * the filter layer before reaching these handlers.
 */
@Tag(name = "Metadata API")
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private MetadataUpgradeWorker upgradeWorker;

    /**
     * Get the MetaModel object by modelName
     *
     * @param modelName model name
     * @return metaModel object
     */
    @GetMapping("/getMetaModel")
    @Operation(summary = "getMetaModel", description = "Get the MetaModel object by modelName")
    @Parameter(name = "modelName", description = "Model name", required = true)
    public ApiResponse<MetaModelDTO> getMetaModelDTO(String modelName) {
        Assert.notBlank(modelName, "Model name cannot be empty.");
        return ApiResponse.success(metadataService.getMetaModelDTO(modelName));
    }

    /**
     * Accept a metadata upgrade envelope from the studio and dispatch it asynchronously.
     * <p>
     * Returns {@code 202 Accepted} as soon as the envelope is validated; the actual
     * upgrade runs on a virtual-thread worker and the result is delivered back to
     * {@code callbackUrl} via {@link MetadataUpgradeWorker}. The studio keeps the
     * corresponding Deployment in {@code DEPLOYING} state until the webhook lands.
     *
     * @param request envelope with packages + callback coordinates
     * @return 202 + empty success body
     */
    @Operation(summary = "upgrade", description = "Dispatch a metadata upgrade (202 Accepted, completion via callback)")
    @PostMapping("/upgrade")
    @RequireSignature
    public ResponseEntity<ApiResponse<Boolean>> upgradePackage(@RequestBody MetadataUpgradeRequest request) {
        Assert.notNull(request, "Metadata upgrade request must not be null!");
        Assert.notEmpty(request.getPackages(), "Metadata upgrade packages must not be empty!");
        Assert.notBlank(request.getCallbackUrl(), "Callback URL is required for async upgrade.");
        Assert.notBlank(request.getCallbackToken(), "Callback token is required for async upgrade.");

        upgradeWorker.runUpgrade(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(true));
    }

    /**
     * Export runtime metadata rows for a version-controlled model, scoped to an app.
     * <p>
     * Used by the studio to compare design-time snapshots with runtime state. The
     * {@code appId} filter is required because a single runtime may host several apps
     * that share the same metadata tables — without it the studio would overwrite one
     * app's design-time state with another app's rows.
     *
     * @param modelName runtime model name
     * @param appId     app id the caller is synchronising
     * @return list of row data maps
     */
    @Operation(summary = "exportRuntimeMetadata", description = "Export runtime metadata rows for a version-controlled model, scoped to an app")
    @PostMapping("/exportRuntimeMetadata")
    @RequireSignature
    public ApiResponse<List<Map<String, Object>>> exportRuntimeMetadata(
            @Parameter(description = "Runtime model name", required = true) @RequestParam String modelName,
            @Parameter(description = "App ID", required = true) @RequestParam Long appId) {
        Assert.notBlank(modelName, "Model name cannot be empty.");
        Assert.notNull(appId, "App id cannot be null.");
        return ApiResponse.success(metadataService.exportRuntimeMetadata(modelName, appId));
    }

    /**
     * Reload metadata.
     * The current replica will be unavailable if an exception occurs during the reload,
     * and the metadata needs to be fixed and reloaded.
     */
    @Operation(summary = "reload")
    @PostMapping("/reload")
    public ApiResponse<Boolean> reloadModelManager() {
        metadataService.reloadMetadata();
        return ApiResponse.success(true);
    }

}
