package io.softa.starter.user.template;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * What the {@code user.invitation} mail and SMS and the {@code user.password-reset} mail are filled
 * with: a link that sets the person's password, valid for a number of days.
 */
@TemplateVariables(name = "user.account-link", label = "Invitation / password reset link")
public record AccountLinkVariables(
        @Schema(title = "Link", description = "Opens the page where the person sets a password",
                example = "https://app.example.com/join?token=…")
        String link,
        @Schema(title = "Expiry (days)", description = "How long the link stays valid", example = "7")
        int expiryDays,
        @Schema(title = "Company name", description = "The company the account belongs to", example = "Acme Pte Ltd")
        String companyName,
        @Schema(title = "Employee name", example = "Alex Chen")
        String employeeName) {
}
