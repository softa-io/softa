package io.softa.framework.base.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Response code enum
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {

    SUCCESS(200, "Success"),

    REDIRECT(302, "Redirect"),

    /** Client Exception */
    BAD_REQUEST(400, "Request parameter error"),
    UNAUTHORIZED(401, "Please login first!"),
    PERMISSION_DENIED(403, "Permission denied"),
    REQUEST_NOT_FOUND(404, "Resource not found"),

    USER_NOT_FOUND(410, "User not found"),
    EMAIL_OR_PASSWORD_ERROR(411, "Email or password error"),
    VERIFICATION_EXCEPTION(412, "Verification exception"),
    TOKEN_EXPIRED(413, "Token invalid or expired"),

    BUSINESS_EXCEPTION(440, "Business exception"),

    HTTP_BAD_METHOD(462, "Request method not supported."),

    VERSION_CHANGED(470, "Data has been modified, please refresh and try again."),

    /** Server exception */
    ERROR(500, "System exception, please feedback to the administrator."),
    BAD_SQL_STATEMENT(510, "SQL Exception"),

    /** External exception */
    EXTERNAL_EXCEPTION(600, "External system exception"),

    ;

    private final Integer code;
    private final String message;
}
