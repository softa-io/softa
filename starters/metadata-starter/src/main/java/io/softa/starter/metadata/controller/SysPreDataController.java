package io.softa.starter.metadata.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.service.SysPreDataService;

/**
 * SysPreData Model Controller
 */
@Tag(name = "SysPreData")
@RestController
@RequestMapping("/SysPreData")
public class SysPreDataController extends EntityController<SysPreDataService, SysPreData, Long> {

    @Operation(summary = "loadPreSystemData", description = """
            Load the specified list of predefined system data files from the root directory resources/data-system,
            supporting data files in JSON, XML, and CSV formats.
            """)
    @PostMapping("/loadPreSystemData")
    public ApiResponse<Boolean> loadPreSystemData(@RequestBody List<String> fileNames) {
        Assert.allNotBlank(fileNames, "The filename of the data to be loaded cannot be empty!");
        service.loadPreSystemData(fileNames);
        return ApiResponse.success(true);
    }

    @Operation(summary = "loadPreTenantData", description = """
            Load the predefined tenant data from resources/data-tenant for the current tenant.
            supporting data files in JSON, XML, and CSV formats.
            """)
    @PostMapping("/loadPreTenantData")
    public ApiResponse<Boolean> loadPreTenantData(@RequestBody List<String> fileNames) {
        Assert.allNotBlank(fileNames, "The filename of the data to be loaded cannot be empty!");
        // Get tenant id from current user in API call
        Long tenantId = ContextHolder.getContext().getTenantId();
        service.loadPreTenantData(fileNames, tenantId);
        return ApiResponse.success(true);
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