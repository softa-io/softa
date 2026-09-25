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
    /**
     * A consultant's authorization for the tenant they are inside has ended — disabled, revoked, or
     * simply past its end date. Its own code because the client's response is specific: leave THIS
     * tenant and go back to the picker, where the person's other memberships may still be waiting.
     * Reported as a plain permission denial it would read as "you lack a permission here", which
     * sends them to a tenant administrator who cannot grant it.
     */
    CONSULTANT_AUTHORIZATION_ENDED(414, "Your authorization for this tenant has ended."),

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
