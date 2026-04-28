package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Categorises one row-level discrepancy between the design-time snapshot and the live
 * runtime, from the operator's reading perspective: snapshot is the expected baseline,
 * runtime is the observed actual. This is intentionally distinct from the deploy-direction
 * {@code AccessType} used by {@code RowChangeDTO} — we don't want UI consumers to reason
 * about "what to do to runtime to make it match snapshot", only about "how runtime drifted".
 */
@Getter
@AllArgsConstructor
public enum DriftKind {
    /** Runtime carries a row the snapshot does not — typically a direct insert on runtime. */
    RUNTIME_ADDED("RuntimeAdded", "Runtime has an extra row missing from the snapshot"),
    /** Snapshot expects a row the runtime no longer has — typically a hard-delete on runtime. */
    RUNTIME_DELETED("RuntimeDeleted", "Snapshot expects a row the runtime no longer has"),
    /** Same row id on both sides but one or more business fields diverged. */
    RUNTIME_MODIFIED("RuntimeModified", "Field values on runtime diverged from the snapshot"),
    ;

    @JsonValue
    private final String value;

    private final String description;
}
