package io.softa.starter.user.template;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * What the {@code user.login-mobile-changed} mail and SMS are filled with: the notice a person gets
 * after changing their own login mobile. Both numbers go in full — it is the owner being told.
 */
@TemplateVariables(name = "user.login-mobile-changed", label = "Login mobile changed")
public record LoginMobileChangedVariables(
        @Schema(title = "Old mobile", example = "+65 9123 4567")
        String oldMobile,
        @Schema(title = "New mobile", example = "+65 9876 5432")
        String newMobile) {
}
