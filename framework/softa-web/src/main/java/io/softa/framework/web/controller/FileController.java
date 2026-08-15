package io.softa.framework.web.controller;

import java.io.Serializable;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.response.ApiResponse;

/**
 * FileController
 *
 * <p>These endpoints authorize themselves, which is unusual here — every other write path runs through
 * {@code ModelService} and inherits the endpoint gate, row scope and field mask from it. They cannot:
 * {@code EndpointIndex} is keyed by URL and the model arrives as a <em>request parameter</em>, so
 * {@code /file/...} resolves to no model and the interceptor can only see them as unregistered. The
 * checks below are what stands in for that.
 *
 * <p>The rule is the same in all four: <b>access to a file derives from access to the row it hangs
 * on</b>. No per-file grant, no new permission — the row's own check answers it, and
 * {@code checkIdAccess} routes through the scope filter, so row scope applies without anything being
 * written for it here.
 */
@Tag(name = "File")
@RestController
@RequestMapping("/file")
public class FileController {

    @Autowired
    private FileService service;

    @Autowired
    private PermissionService permissionService;

    /**
     * Get the fileInfo by fileId
     */
    @Operation(description = "Get the fileInfo by fileId")
    @GetMapping(value = "/getByFileId")
    @Parameter(name = "fileId", description = "The id of the file object.")
    public ApiResponse<FileInfo> getByFileId(@RequestParam Long fileId) {
        Assert.notNull(fileId, "fileId cannot be empty.");
        service.getFileOwner(fileId).ifPresent(this::assertCanRead);
        return ApiResponse.success(service.getByFileId(fileId).orElse(null));
    }

    /**
     * Authorize a read against whatever owns the file.
     *
     * <p>Claimed by a row — the ordinary case, and the one the feature exists for: an employee uploads an
     * attachment and HR reads it, because HR can read the employee. Unclaimed means uploaded but not yet
     * saved onto anything, so there is no record to authorize against and the only defensible owner is
     * the uploader; ids are sequential enough that leaving that window open would be an invitation.
     */
    private void assertCanRead(FileService.FileOwner owner) {
        if (owner.isUnclaimed()) {
            // An administrator bypasses every other data-plane check; a hand-written id comparison
            // would be the one place that denies them, which reads as a bug rather than as a rule.
            if (permissionService.isDataPlaneExempt()) {
                return;
            }
            Long currentUser = ContextHolder.getContext().getUserId();
            if (currentUser == null || !currentUser.equals(owner.uploaderId())) {
                throw new PermissionException("This file is not yours to read.");
            }
            return;
        }
        permissionService.checkIdAccess(owner.modelName(),
                IdUtils.formatId(owner.modelName(), owner.rowId()), AccessType.READ);
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
        // Attaching a file to a row is a change to that row, so it asks the row's own write check.
        permissionService.checkIdAccess(modelName, IdUtils.formatId(modelName, rowId), AccessType.UPDATE);
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
        if (rowId != null) {
            permissionService.checkIdAccess(modelName, IdUtils.formatId(modelName, rowId), AccessType.UPDATE);
        }
        // A null rowId is the create-form case — the record does not exist yet, so there is no row to
        // check, and no model-level gate to fall back on: creating any model is granted by default here,
        // so such a check would answer "yes" every time. What bounds it instead is the claim: a file only
        // enters business data when a row references it, and that write is itself checked. An unclaimed
        // file is meanwhile readable only by whoever uploaded it, and the claim refuses to move a file
        // that another row already owns. Left deliberately open — wiki Permissions-Fix-File-Endpoint-Gate
        // §4.2.3.
        return ApiResponse.success(service.uploadFile(modelName, rowId, fieldName, file));
    }
}