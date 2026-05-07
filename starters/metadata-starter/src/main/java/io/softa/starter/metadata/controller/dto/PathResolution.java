package io.softa.starter.metadata.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.meta.CascadeFieldWalker;

/**
 * Resolution outcome of a single cascaded path. On success {@link #metaField}
 * carries the leaf field; on failure {@link #errorCode} / {@link #errorAt} /
 * {@link #message} describe the structural issue. Per-path failures do not
 * fail the enclosing request.
 */
@Data
@Schema(name = "PathResolution")
public class PathResolution {

    private String path;
    private boolean ok;

    /** Set when {@code ok == true}. */
    private MetaFieldDTO metaField;

    /** Set when {@code ok == false}. */
    private CascadeFieldWalker.ErrorKind errorCode;

    /** Zero-based segment index of the failure; set when {@code ok == false}. */
    private Integer errorAt;

    /** Human-readable diagnostic; set when {@code ok == false}. */
    private String message;

    public static PathResolution success(String path, MetaFieldDTO metaField) {
        PathResolution r = new PathResolution();
        r.setPath(path);
        r.setOk(true);
        r.setMetaField(metaField);
        return r;
    }

    public static PathResolution failure(String path, CascadeFieldWalker.ErrorKind code, int errorAt, String message) {
        PathResolution r = new PathResolution();
        r.setPath(path);
        r.setOk(false);
        r.setErrorCode(code);
        r.setErrorAt(errorAt);
        r.setMessage(message);
        return r;
    }
}
