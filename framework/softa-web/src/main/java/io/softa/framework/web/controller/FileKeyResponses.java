package io.softa.framework.web.controller;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.softa.framework.orm.file.FileOpenKeys.KeyCheck;
import io.softa.framework.orm.service.FileService;

/**
 * What the two file-key endpoints answer: a 302 to a storage link signed now, or a status with one
 * line of plain text.
 *
 * <p>Plain text, not the JSON envelope: these addresses are opened directly by img, iframe and
 * new-tab navigation, where a JSON body is never read. Every answer is {@code no-store}: a shared cache
 * holding the 302 for one account's key would hand the file to anyone requesting that address.
 */
final class FileKeyResponses {

    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    static final String NOT_FOUND = "This file is not available.";
    static final String WRONG_ACCOUNT = "This file link was issued to another account. Open the file from its page.";
    static final String EXPIRED = "This file link has expired. Open the file again from its page.";

    private FileKeyResponses() {
    }

    /**
     * Answer a checked key.
     *
     * @param check    the outcome of checking the key
     * @param files    where the storage link comes from
     * @param download whether to ask the storage for a download rather than inline display
     * @return the response
     */
    static ResponseEntity<String> open(KeyCheck check, FileService files, boolean download) {
        return switch (check.status()) {
            case MALFORMED -> text(HttpStatus.NOT_FOUND, NOT_FOUND);
            case WRONG_SIGNATURE -> text(HttpStatus.FORBIDDEN, WRONG_ACCOUNT);
            case EXPIRED -> text(HttpStatus.GONE, EXPIRED);
            case VALID -> files.getStorageLink(check.fileId(), download)
                    .map(link -> ResponseEntity.status(HttpStatus.FOUND)
                            .cacheControl(CacheControl.noStore())
                            .header(HttpHeaders.LOCATION, link)
                            .<String>build())
                    .orElseGet(() -> text(HttpStatus.NOT_FOUND, NOT_FOUND));
        };
    }

    /** {@code download=1} or {@code download=true} asks for a download. */
    static boolean isDownload(String download) {
        return "1".equals(download) || "true".equalsIgnoreCase(download);
    }

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(TEXT).body(message);
    }
}
