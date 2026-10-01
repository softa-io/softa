package io.softa.starter.metadata.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.seed.SeedLevel;
import io.softa.starter.metadata.seed.SeedSyncService;
import io.softa.starter.metadata.service.SysPreDataService;

/**
 * SysPreData Model Controller.
 *
 * <p>Platform files are loaded by name through the seed sync, so the load is recorded like any other. Tenant
 * files are not loaded from here at all: a tenant is set up with them when it is provisioned, and brought up to
 * date by a seed sync, which only adds what it does not have — loading them whole would overwrite what the
 * tenant changed.
 */
@Tag(name = "SysPreData")
@RestController
@RequestMapping("/SysPreData")
public class SysPreDataController extends EntityController<SysPreDataService, SysPreData, Long> {

    private final SeedSyncService seedSyncService;

    public SysPreDataController(SeedSyncService seedSyncService) {
        this.seedSyncService = seedSyncService;
    }

    @Operation(summary = "loadPreSystemData", description = """
            Load the named platform-global seed files from resources/data-system as a seed sync batch, whether
            or not they changed, and return the batch id; its progress shows on the seed sync page. Each file
            must be in the seed manifest at that level. Without a seed manifest the files are loaded here and
            no batch is returned.
            """)
    @PostMapping("/loadPreSystemData")
    public ApiResponse<Long> loadPreSystemData(@RequestBody List<String> fileNames) {
        Assert.allNotBlank(fileNames, "The filename of the data to be loaded cannot be empty!");
        if (!seedSyncService.hasManifest()) {
            service.loadPreSystemData(fileNames);
            return ApiResponse.success(null);
        }
        return ApiResponse.success(seedSyncService.loadFiles(fileNames, SeedLevel.PLATFORM_GLOBAL).getId());
    }

    @Operation(summary = "loadPrePlatformData", description = """
            Load the named platform-tier seed files from resources/data-platform — rows on the platform tier
            (tenantId = -1) of multiTenant models — as a seed sync batch, whether or not they changed, and return
            the batch id. Each file must be in the seed manifest at that level. Without a seed manifest the files
            are loaded here and no batch is returned.
            """)
    @PostMapping("/loadPrePlatformData")
    public ApiResponse<Long> loadPrePlatformData(@RequestBody List<String> fileNames) {
        Assert.allNotBlank(fileNames, "The filename of the data to be loaded cannot be empty!");
        if (!seedSyncService.hasManifest()) {
            service.loadPrePlatformData(fileNames);
            return ApiResponse.success(null);
        }
        return ApiResponse.success(seedSyncService.loadFiles(fileNames, SeedLevel.PLATFORM_TENANT).getId());
    }

    @Operation(summary = "loadSystemDataByUpload", description = """
            Upload a predefined data file to load data.
            The file should be in JSON, XML, or CSV format and follow the required structure for predefined data.
            """)
    @PostMapping("/loadSystemDataByUpload")
    @SwitchUser(value = SystemUser.INTEGRATION_USER)
    public ApiResponse<Boolean> uploadPreSystemData(@RequestParam("file") MultipartFile file) {
        Assert.notTrue(file.isEmpty(), "The file to be uploaded cannot be empty!");
        service.loadPreSystemData(file);
        return ApiResponse.success(true);
    }
}