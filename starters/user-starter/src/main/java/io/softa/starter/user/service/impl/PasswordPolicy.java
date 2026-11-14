package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.List;

import io.softa.framework.base.exception.BusinessException;

/**
 * The password rules, in one place so the server and the on-screen checklist cannot drift.
 *
 * <ol>
 *   <li>8–32 characters;</li>
 *   <li>contains an upper-case AND a lower-case letter;</li>
 *   <li>contains a digit.</li>
 * </ol>
 *
 * <p>Nothing else: spaces and special characters are allowed and not required — requiring special
 * characters pushes people towards predictable substitutions. The three rules are the ones every
 * password screen shows; a rule the screen does not show would refuse a password the person was
 * told is fine.
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 32;

    private PasswordPolicy() {
    }

    /**
     * Validate a candidate password, throwing with EVERY unmet rule rather than the first.
     *
     * <p>Reporting one at a time turns setting a password into a guessing game — the screen shows a
     * checklist, so the server has to be able to say which items failed.
     *
     * @throws BusinessException listing every rule the password fails
     */
    public static void validate(String password) {
        List<String> failures = check(password);
        if (!failures.isEmpty()) {
            throw new BusinessException("Password does not meet the requirements: "
                    + String.join("; ", failures));
        }
    }

    /** The unmet rules, in checklist order; empty when the password is acceptable. */
    public static List<String> check(String password) {
        List<String> failures = new ArrayList<>();
        if (password == null) {
            failures.add("8–32 characters");
            return failures;
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            failures.add("8–32 characters");
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        if (!upper || !lower) {
            failures.add("contains uppercase and lowercase letters");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            failures.add("contains numbers");
        }
        return failures;
    }
}
