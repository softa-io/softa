package io.softa.starter.metadata.sequence.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Sequence allocation mode. Determines whether the counter UPDATE shares
 * the caller's transaction lifecycle.
 *
 * <ul>
 *   <li>{@link #NO_GAP}: counter UPDATE joins caller's transaction
 *       ({@code @Transactional(propagation = MANDATORY)}); business rollback
 *       rolls the counter back. Strict no-gap, but row lock held for the
 *       entire business transaction.</li>
 *   <li>{@link #ALLOW_GAP}: counter UPDATE runs in a new transaction
 *       ({@code @Transactional(propagation = REQUIRES_NEW)}) that commits
 *       independently. Row lock held only for the inner allocation
 *       (~5 ms); business rollback leaves the counter advanced — a number
 *       can be skipped. Used for high-throughput cases where continuity
 *       is not required.</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum SequenceMode {
    NO_GAP("NoGap"),
    ALLOW_GAP("AllowGap"),
    ;
    @JsonValue
    private final String mode;
}
