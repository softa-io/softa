package io.softa.framework.base.exception;

import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.ResponseCode;

/**
 * Permission exception
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PermissionException extends BaseException {

    private ResponseCode responseCode = ResponseCode.PERMISSION_DENIED;

    /**
     * Field → sentence, for a refusal of particular fields written for the person who sent them. Empty
     * for every other refusal, whose message is meant for the server log rather than the screen — a
     * missing endpoint registration, a path outside the caller's scope — and which a client therefore
     * shows only as a generic denial. Carried in the response body as {@code fieldErrors}.
     */
    private Map<String, String> fieldErrors = Map.of();

    /**
     * Accepts variable arguments, optionally ending with a Throwable for enhanced error tracking.
     */
    public PermissionException(String message, Object... args) {
        super(message, args);
    }

    /**
     * A refusal of one field, worded for the user: the sentence is the message and is recorded against
     * the field, so a client can show it in place of a generic denial.
     */
    public static PermissionException forField(String field, String message, Object... args) {
        PermissionException e = new PermissionException(message, args);
        e.setFieldErrors(Map.of(field, e.getMessage()));
        return e;
    }
}
