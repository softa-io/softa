package io.softa.starter.user.template;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariableSet;

/**
 * What the {@code user.verification-code} mail and SMS are filled with: a one-time code for signing
 * in, joining, or confirming a change of credentials.
 */
@TemplateVariableSet(name = "user.verification-code", label = "Verification code")
public record VerificationCodeVariables(
        @Schema(title = "Code", description = "The one-time code the person types in", example = "482913")
        String code,
        @Schema(title = "Expiry (minutes)", description = "How long the code stays valid", example = "5")
        int expiryMinutes,
        @Schema(title = "Employee name", description = "Whom the mail greets; empty in an SMS and when "
                + "the person is not known yet", example = "Alex Chen")
        String employeeName) {

    /** A code sent where nobody is greeted by name — an SMS. */
    public VerificationCodeVariables(String code, int expiryMinutes) {
        this(code, expiryMinutes, null);
    }
}
