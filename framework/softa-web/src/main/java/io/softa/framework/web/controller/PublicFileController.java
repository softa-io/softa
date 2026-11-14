package io.softa.framework.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.file.FileOpenKeys;
import io.softa.framework.orm.file.FileOpenKeys.KeyCheck;
import io.softa.framework.orm.service.FileService;

/**
 * Opens unbound file keys: the {@code FileInfo.url} of a request without a logged-in user — a public
 * page under {@code /openapi/**}, a scheduled job, a flow nobody triggered.
 *
 * <p>Public, like everything under {@code /openapi/**}: an unbound key is the authorization, and it
 * only comes out of a read that was authorized already (a public page behind its verification code).
 * It opens for anyone holding it, for an hour. Keys handed to a logged-in user open at
 * {@code /file/open/{key}} instead, for that account only.
 */
@Tag(name = "File")
@RestController
@RequestMapping("/openapi/file")
public class PublicFileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOpenKeys fileOpenKeys;

    /**
     * Redirect to a storage link signed now for the file an unbound key names.
     *
     * @param key      the key from {@code FileInfo.url}
     * @param download {@code 1} to download rather than display inline
     * @return 302 to the storage link; 404 for a malformed key or a missing file; 403 for a key we did
     *         not sign as an unbound one; 410 for an expired key
     */
    @Operation(description = "Open a file by an unbound key from FileInfo.url: redirects to a short-lived storage link.")
    @GetMapping("/{key}")
    public ResponseEntity<String> open(@PathVariable String key,
                                       @Parameter(description = "1 to download rather than display inline")
                                       @RequestParam(required = false) String download) {
        KeyCheck check = fileOpenKeys.checkUnboundKey(key);
        // /openapi/** binds no context: read the file record as the system, across tenants. The key
        // names the file, and it was checked above.
        return ContextUtils.inSystemContext(
                () -> FileKeyResponses.open(check, fileService, FileKeyResponses.isDownload(download)));
    }
}
