package io.softa.starter.metadata.controller;

import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.service.SysPreDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * SysPreData Model Controller
 */
@Tag(name = "SysPreData")
@RestController
@RequestMapping("/SysPreData")
public class SysPreDataController extends EntityController<SysPreDataService, SysPreData, Long> {

    /**
     * Load the specified list of predefined data files from the root directory: resources/data.
     * Supports data files in JSON, XML, and CSV formats.
     * @return success or not
     */
    @Operation(summary = "loadData", description = """
            Load the specified list of predefined data files from the root directory resources/data,
            supporting data files in JSON, XML, and CSV formats.
            """)
    @PostMapping("/loadData")
    public ApiResponse<Boolean> loadPredefinedData(@RequestBody List<String> fileNames) {
        Assert.allNotBlank(fileNames, "The filename of the data to be loaded cannot be empty!");
        service.loadPredefinedData(fileNames);
        return ApiResponse.success(true);
    }


    /**
     * Upload a predefined data file to load data.
     *
     * @param file the multipart file containing the predefined data to be loaded
     * @return success or not
     */
    @Operation(summary = "uploadFile")
    @PostMapping("/uploadFile")
    @SwitchUser(value = SystemUser.INTEGRATION_USER)
    public ApiResponse<Boolean> uploadPredefinedData(@RequestParam("file") MultipartFile file) {
        Assert.notTrue(file.isEmpty(), "The file to be uploaded cannot be empty!");
        service.loadPredefinedData(file);
        return ApiResponse.success(true);
    }
}