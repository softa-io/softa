package io.softa.starter.metadata.controller;

import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.web.dto.MetadataUpgradePackage;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.service.MetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Metadata Upgrade controller
 */
@Tag(name = "Metadata Upgrade API")
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private MetadataService metadataService;

    /**
     * Upgrades the metadata of multiple models, all within a single transaction
     * to avoid refreshing the model pool repeatedly and missing dependency data.
     *
     * @param metadataPackages the metadata packages to upgrade
     * @return Success or not
     */
    @Operation(summary = "upgrade")
    @PostMapping("/upgrade")
    @SwitchUser(value = SystemUser.INTEGRATION_USER)
    public ApiResponse<Boolean> upgradePackage(@RequestBody List<MetadataUpgradePackage> metadataPackages) {
        Assert.notEmpty(metadataPackages, "Metadata upgrade data must not be empty!");
        metadataService.upgradeMetadata(metadataPackages);
        metadataService.reloadMetadata();
        return ApiResponse.success(true);
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