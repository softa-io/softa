package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PRD D4, rule by rule. The four failure strings are asserted verbatim because the screen renders
 * a four-item checklist from them — a reworded message silently breaks the mapping.
 */
class PasswordPolicyTest {


    @Test
    void aCompliantPasswordPasses() {
        assertThatCode(() -> PasswordPolicy.validate("Str0ngPass"))
                .doesNotThrowAnyException();
    }

    @Test
    void specialCharactersAreAllowedButNotRequired() {
        // D4 chose not to require them: mandating specials pushes people towards predictable
        // substitutions. Both shapes must pass.
        assertThat(PasswordPolicy.check("Str0ngPass")).isEmpty();
        assertThat(PasswordPolicy.check("Str0ng!Pass#")).isEmpty();
    }

    @Test
    void lengthBounds() {
        assertThat(PasswordPolicy.check("Sh0rtPw")).contains("8–32 characters");
        assertThat(PasswordPolicy.check("A1" + "x".repeat(31)))
                .contains("8–32 characters");
        // Exactly at the bounds is acceptable.
        assertThat(PasswordPolicy.check("Str0ngPw")).isEmpty();
        assertThat(PasswordPolicy.check("Str0ng" + "x".repeat(26))).isEmpty();
    }

    @Test
    void needsBothCases() {
        assertThat(PasswordPolicy.check("str0ngpass"))
                .contains("contains uppercase and lowercase letters");
        assertThat(PasswordPolicy.check("STR0NGPASS"))
                .contains("contains uppercase and lowercase letters");
    }

    @Test
    void needsADigit() {
        assertThat(PasswordPolicy.check("StrongPass")).contains("contains numbers");
    }

    @Test
    void spacesAndTheirOwnContactDetails_areAllowed() {
        // Three rules, as every password screen shows them; a fourth the screen does not show would
        // refuse a password the person was told is fine.
        assertThat(PasswordPolicy.check("Str0ng Pass")).isEmpty();
        assertThat(PasswordPolicy.check("Alice5678x")).isEmpty();
    }

    @Test
    void reportsEveryUnmetRuleAtOnce() {
        // The screen shows three checklist items; returning only the first failure would turn
        // setting a password into a guessing game.
        assertThat(PasswordPolicy.check("abc")).hasSize(3)
                .containsExactly("8–32 characters",
                        "contains uppercase and lowercase letters",
                        "contains numbers");
    }

    @Test
    void nullIsRejectedAsTooShort() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("8–32 characters");
    }
}
