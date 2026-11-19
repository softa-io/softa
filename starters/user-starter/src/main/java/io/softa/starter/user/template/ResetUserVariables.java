package io.softa.starter.user.template;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * What the {@code user.reset-user} mail and SMS are filled with: the notice sent when an
 * administrator resets someone's login mobile. The new number is masked — the old number, where the
 * SMS goes, may be in a stranger's hands.
 */
@TemplateVariables(name = "user.reset-user", label = "Login reset by an administrator")
public record ResetUserVariables(
        @Schema(title = "Employee name", example = "Alex Chen")
        String employeeName,
        @Schema(title = "Company name", example = "Acme Pte Ltd")
        String companyName,
        @Schema(title = "New mobile (masked)", example = "+65 98** **32")
        String newMobile,
        @Schema(title = "Time", description = "When the reset was made, in the company's time zone",
                example = "2026-11-18 14:30")
        String time) {
}
