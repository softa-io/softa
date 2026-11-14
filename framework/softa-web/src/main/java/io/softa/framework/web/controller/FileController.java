package io.softa.framework.web.controller;

import java.io.Serializable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.file.FileOpenKeys;
import io.softa.framework.orm.file.FileOpenKeys.KeyCheck;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.utils.IdUtils;
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

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FileOpenKeys fileOpenKeys;

    /**
     * Authorize attaching a file to an existing row — a change to that row, so it asks what a change to
     * that row asks.
     *
     * <p>Two questions, because one cannot answer the other. <b>Which rows</b> is
     * {@code checkIdAccess}: it resolves to "is this id inside your scope", so it stops someone
     * attaching to a record they cannot even see. <b>Whether at all</b> is
     * {@code hasModelActionGrant}: {@code checkIdAccess} reads the same scope whatever access type it
     * is given, so on its own it lets a read-only caller write to every row they can read — and the
     * endpoint gate, which would normally have asked, cannot match these URLs and had to whitelist
     * them instead.
     */
    private void assertCanAttach(String modelName, Serializable rowId) {
        if (!permissionService.hasModelActionGrant(modelName, AccessType.UPDATE)) {
            throw new PermissionException("You may not attach files to " + modelName + ".");
        }
        permissionService.checkIdAccess(modelName, IdUtils.formatId(modelName, rowId), AccessType.UPDATE);
    }

    /**
     * Authorize a create-form upload — no row exists yet, so only the "whether at all" half of
     * {@link #assertCanAttach} can be asked. CREATE or UPDATE both qualify, because both kinds of form
     * upload before their save carries a row id. Without this gate, any signed-in user could pour
     * bytes into storage against any model: the files stay unreadable (unclaimed, reachable through no
     * row), but storage is spent all the same.
     */
    private void assertMayUploadFor(String modelName) {
        if (!permissionService.hasModelActionGrant(modelName, AccessType.CREATE)
                && !permissionService.hasModelActionGrant(modelName, AccessType.UPDATE)) {
            throw new PermissionException("You may not attach files to " + modelName + ".");
        }
    }

    // No read by id, by design. A file is never fetched by its own id or by (model, row) over HTTP —
    // both would be a direct object reference the row-derived model cannot authorize (a bare id names
    // no row; a row id an admin passes skips the tenant predicate). Business callers read the owning
    // row through ModelService, and the framework expands its File / MultiFile fields into FileInfo
    // under the row's own scope and tenant. Its url carries a key (FileOpenKeys) that /open/{key} below
    // turns into a storage link: the key is only handed out by such an authorized read, it opens for
    // the account it was issued to and nobody else, and it expires — so the endpoint takes no id and
    // asks no question the read did not already answer. The writes below are uploads, which authorize
    // against the row they attach to.

    /**
     * Redirect to a storage link signed now for the file a user key names.
     *
     * <p>The key must have been issued to the account of this session: anyone else gets 403, even
     * with the address in hand. The endpoint gate cannot tell a model from this path, so applications
     * list {@code /file/open/**} among the authenticated bypass patterns — the key is the check.
     *
     * @param key      the key from {@code FileInfo.url}
     * @param download {@code 1} to download rather than display inline
     * @return 302 to the storage link; 404 for a malformed key or a missing file; 403 for a key issued
     *         to another account; 410 for an expired key
     */
    @Operation(description = "Open a file by the key in its FileInfo.url: redirects to a short-lived storage link.")
    @GetMapping("/open/{key}")
    public ResponseEntity<String> open(@PathVariable String key,
                                       @Parameter(description = "1 to download rather than display inline")
                                       @RequestParam(required = false) String download) {
        KeyCheck check = fileOpenKeys.checkUserKey(key, ContextHolder.getContext().getUserId());
        return FileKeyResponses.open(check, service, FileKeyResponses.isDownload(download));
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
        assertCanAttach(modelName, rowId);
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
            assertCanAttach(modelName, rowId);
        } else {
            // The create-form case: the record does not exist yet, so there is no row to check
            // against — but whether this caller may write the model at all is still answerable. What
            // bounds ACCESS is the save: the file only enters business data when a row references it,
            // and that write runs FileOwnership.validate, which refuses an id this model does not
            // own. Until then the record is unclaimed and reachable through no row at all.
            assertMayUploadFor(modelName);
        }
        return ApiResponse.success(service.uploadFile(modelName, rowId, fieldName, file));
    }
}