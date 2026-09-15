package io.softa.framework.orm.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;

/**
 * What an in-memory filter evaluation can see besides the row itself.
 *
 * <p>Two kinds of thing: the reserved variables a condition may name in the field slot
 * ({@code @mode}, {@code @userId}) and the environment tokens it may name as a value
 * ({@code {{ USER_ID }}}, {@code {{ TODAY }}}, {@code {{ NOW }}}). Both are fixed for the whole
 * evaluation of one write, which is why they travel as one immutable value rather than being read
 * from thread-locals inside the evaluator — a test can hand in a Tuesday, and the frontend's
 * evaluator receives the same values by other means.
 *
 * @param mode the write being evaluated ({@code CREATE} / {@code UPDATE}); {@code @mode} compares
 *             against its lower-case name, matching the frontend's {@code create} / {@code update}
 * @param userId the current user, or null when there is none (seed loading, system jobs)
 * @param today the calendar day {@code TODAY} resolves to; date offsets are applied to it
 * @param now the instant {@code NOW} resolves to
 */
public record EvalContext(AccessType mode, Long userId, LocalDate today, LocalDateTime now) {

    /** The context for a write happening now, by the user bound to the current request. */
    public static EvalContext of(AccessType mode) {
        LocalDateTime now = LocalDateTime.now();
        return new EvalContext(mode, ContextHolder.getContext().getUserId(), now.toLocalDate(), now);
    }

    /** The value {@code @mode} takes in a condition: the access type's lower-case name. */
    public String modeName() {
        return mode == null ? null : mode.name().toLowerCase();
    }
}
