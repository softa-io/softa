package io.softa.starter.user.template;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * What the {@code user.enterprise-email-changed} mail and SMS are filled with: the notice sent when
 * someone's enterprise email is changed. The new address is masked — the old address, where the
 * mail goes, may no longer be the person's.
 */
@TemplateVariables(name = "user.enterprise-email-changed", label = "Enterprise email changed")
public record EnterpriseEmailChangedVariables(
        @Schema(title = "Employee name", example = "Alex Chen")
        String employeeName,
        @Schema(title = "Company name", example = "Acme Pte Ltd")
        String companyName,
        @Schema(title = "New email (masked)", example = "a***@example.com")
        String newEmail,
        @Schema(title = "Time", description = "When the change was made, in the company's time zone",
                example = "2026-11-18 14:30")
        String time) {
}
