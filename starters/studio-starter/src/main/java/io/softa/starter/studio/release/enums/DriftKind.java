package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Categorises one row-level discrepancy between the env's design and the live
 * runtime, from the operator's reading perspective: the design is the expected baseline,
 * runtime is the observed actual. This is intentionally distinct from the deploy-direction
 * {@code AccessType} used by {@code RowChangeDTO} — we don't want UI consumers to reason
 * about "what to do to runtime to make it match the design", only about "how runtime drifted".
 */
@Getter
@AllArgsConstructor
public enum DriftKind {
    /** Runtime carries a row the design does not — typically a direct insert on runtime. */
    RUNTIME_ADDED("RuntimeAdded"),
    /** The design expects a row the runtime no longer has — typically a hard-delete on runtime. */
    RUNTIME_DELETED("RuntimeDeleted"),
    /** Same row id on both sides but one or more business fields diverged. */
    RUNTIME_MODIFIED("RuntimeModified"),
    ;

    @JsonValue
    private final String value;
}
