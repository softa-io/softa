package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.service.MetadataService;

/**
 * Metadata Upgrade controller.
 */
@Tag(name = "Metadata API")
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private MetadataService metadataService;

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
     * Reload metadata.
     * The current replica will be unavailable if an exception occurs during the reload,
     * and the metadata needs to be fixed and reloaded.
     */
    @Operation(summary = "reloadMetadata")
    @PostMapping("/reloadMetadata")
    public ApiResponse<Boolean> reloadMetadata() {
        metadataService.reloadMetadata();
        return ApiResponse.success(true);
    }

}
