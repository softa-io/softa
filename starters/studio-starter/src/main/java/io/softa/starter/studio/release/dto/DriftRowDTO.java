package io.softa.starter.studio.release.dto;

import java.util.Map;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.studio.release.enums.DriftKind;

/**
 * Drift-oriented view of a single row-level discrepancy, served by the read-only drift
 * API for UI consumption.
 * <p>
 * Field semantics are anchored to the operator's perspective rather than the deploy
 * direction used internally by {@link RowChangeDTO}:
 * <ul>
 *   <li>{@code expected} always reflects the design side (= what the env's design says the
 *       runtime should look like).</li>
 *   <li>{@code actual} always reflects the runtime side (= what the runtime actually has now).</li>
 * </ul>
 * Avoiding {@code before}/{@code after} naming on purpose — those words suggest a time
 * order which depends on which side you stand on, and have caused confusion before.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DriftRow", description = "One row-level discrepancy between the env design and runtime")
public class DriftRowDTO {

    @Schema(description = "Logical model name on the design side")
    private String model;

    @Schema(description = "Kind of drift, from the design-vs-runtime viewpoint")
    private DriftKind kind;

    /**
     * Design side of the row.
     * <ul>
     *   <li>{@link DriftKind#RUNTIME_ADDED}: {@code null} (design has no row)</li>
     *   <li>{@link DriftKind#RUNTIME_DELETED}: full design row</li>
     *   <li>{@link DriftKind#RUNTIME_MODIFIED}: only the diverged fields, design values</li>
     * </ul>
     */
    @Schema(description = "Design side; null when the design has no row for this id")
    private Map<String, Object> expected;

    /**
     * Runtime side of the row.
     * <ul>
     *   <li>{@link DriftKind#RUNTIME_ADDED}: full runtime row</li>
     *   <li>{@link DriftKind#RUNTIME_DELETED}: {@code null} (runtime has no row)</li>
     *   <li>{@link DriftKind#RUNTIME_MODIFIED}: only the diverged fields, runtime values</li>
     * </ul>
     */
    @Schema(description = "Runtime side; null when runtime has no row for this id")
    private Map<String, Object> actual;

    @Schema(description = "Field names that diverge — populated only for RUNTIME_MODIFIED")
    private Set<String> changedFields;
}
