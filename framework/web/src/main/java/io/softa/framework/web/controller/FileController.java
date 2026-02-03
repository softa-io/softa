package io.softa.framework.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.Serializable;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.web.response.ApiResponse;

/**
 * FileController
 */
@Tag(name = "File")
@RestController
@RequestMapping("/file")
public class FileController {

    @Autowired
    private FileService service;

    /**
     * Get the fileInfo by fileId
     */
    @Operation(description = "Get the fileInfo by fileId")
    @GetMapping(value = "/getByFileId")
    @Parameter(name = "fileId", description = "The id of the file object.")
    public ApiResponse<FileInfo> getByFileId(@RequestParam String fileId) {
        Assert.notBlank(fileId, "fileId cannot be empty.");
        return ApiResponse.success(service.getByFileId(fileId).orElse(null));
    }

    /**
     * Get the fileInfo by modelName and rowId
     */
    @Operation(description = "Get the fileInfos by modelName and rowId")
    @GetMapping(value = "/getRowFiles")
    @Parameters({
            @Parameter(name = "modelName", description = "The model name of the file belongs to"),
            @Parameter(name = "rowId", description = "The row ID of the file belongs to"),
    })
    public ApiResponse<List<FileInfo>> getRowFiles(@RequestParam String modelName,
                                                   @RequestParam Serializable rowId) {
        Assert.notBlank(modelName, "modelName cannot be empty.");
        Assert.notNull(rowId, "rowId cannot be null.");
        return ApiResponse.success(service.getRowFiles(modelName, rowId));
    }

    /**
     * Upload a file to the specified model and rowId, and return the fileInfo.
     *
     * @param modelName The model name of the file belongs to
     * @param rowId The row ID of the file belongs to
     * @param file The file to be uploaded
     * @return The fileInfo of the uploaded file
     */
    @Operation(description = "Upload a file to the specified model and row, and return the fileInfo.")
    @PostMapping("/uploadFileToRow")
    @Parameters({
            @Parameter(name = "modelName", description = "The model name of the file belongs to"),
            @Parameter(name = "rowId", description = "The row ID of the file belongs to"),
            @Parameter(name = "file", description = "The file to be uploaded")
    })
    public ApiResponse<FileInfo> uploadFileToRow(@RequestParam String modelName,
                                                 @RequestParam Serializable rowId,
                                                 @RequestParam MultipartFile file) {
        Assert.notBlank(modelName, "modelName cannot be empty.");
        Assert.notNull(rowId, "rowId cannot be null.");
        Assert.notTrue(file.isEmpty(), "The file to upload cannot be empty!");
        return ApiResponse.success(service.uploadFile(modelName, rowId, null, file));
    }

    /**
     * Upload a file to the specified model, rowId and fieldName, and return the fileInfo.
     *
     * @param modelName The model name of the file belongs to
     * @param rowId The row ID of the file belongs to, can be null in create mode
     * @param fieldName The field name of the file belongs to
     * @param file The file to be uploaded
     * @return The fileInfo of the uploaded file
     */
    @Operation(description = "Upload a file to the specified model and row, and return the fileInfo.")
    @PostMapping("/uploadFileToField")
    @Parameters({
            @Parameter(name = "modelName", description = "The model name of the file belongs to"),
            @Parameter(name = "rowId", description = "The row ID of the file belongs to, can be null in create mode"),
            @Parameter(name = "fieldName", description = "The field name of the file belongs to"),
            @Parameter(name = "file", description = "The file to be uploaded")
    })
    public ApiResponse<FileInfo> uploadFileToField(@RequestParam String modelName,
                                                 @RequestParam(required = false) Serializable rowId,
                                                 @RequestParam String fieldName,
                                                 @RequestParam MultipartFile file) {
        Assert.notBlank(modelName, "modelName cannot be empty.");
        Assert.notTrue(file.isEmpty(), "The file to upload cannot be empty!");
        return ApiResponse.success(service.uploadFile(modelName, rowId, fieldName, file));
    }
}