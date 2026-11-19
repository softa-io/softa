package io.softa.starter.user.template;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * What the four subscription reminders are filled with — {@code subscription.expiry-reminder},
 * {@code .trial-expiry-reminder}, {@code .gap-reminder} and {@code .downgrade-reminder}. One set for
 * all four: which mail goes out depends on what follows the plan, and a reminder that has nothing
 * following leaves the last two empty.
 */
@TemplateVariables(name = "subscription.reminder", label = "Subscription reminder")
public record SubscriptionReminderVariables(
        @Schema(title = "Tenant name", example = "Acme Pte Ltd")
        String tenantName,
        @Schema(title = "Plan", description = "The plan that is ending", example = "Enterprise")
        String planId,
        @Schema(title = "Expiry date", example = "2026-12-31")
        String expiryDate,
        @Schema(title = "Days left", example = "14")
        int daysLeft,
        @Schema(title = "Next start date", description = "When the following plan starts, if it starts "
                + "later than this one ends", example = "2027-01-15")
        String nextStartDate,
        @Schema(title = "Next plan", description = "The plan that follows, when it is a different one",
                example = "Standard")
        String successorPlanId) {
}
