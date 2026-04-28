package io.softa.starter.studio.release.dto;

import java.util.Map;
import java.util.Set;

import io.softa.starter.studio.release.enums.DriftKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Drift-oriented view of a single row-level discrepancy, served by the read-only drift
 * API for UI consumption.
 * <p>
 * Field semantics are anchored to the operator's perspective rather than the deploy
 * direction used internally by {@link RowChangeDTO}:
 * <ul>
 *   <li>{@code expected} always reflects the snapshot side (= what the last-deployed
 *       design state promised runtime should look like).</li>
 *   <li>{@code actual} always reflects the runtime side (= what the runtime actually has now).</li>
 * </ul>
 * Avoiding {@code before}/{@code after} naming on purpose — those words suggest a time
 * order which depends on which side you stand on, and have caused confusion before.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DriftRow", description = "One row-level discrepancy between snapshot and runtime")
public class DriftRowDTO {

    @Schema(description = "Logical model name on the design side")
    private String model;

    @Schema(description = "Primary id of the drifted row")
    private Long rowId;

    @Schema(description = "Kind of drift, from the snapshot-vs-runtime viewpoint")
    private DriftKind kind;

    /**
     * Snapshot side of the row.
     * <ul>
     *   <li>{@link DriftKind#RUNTIME_ADDED}: {@code null} (snapshot has no row)</li>
     *   <li>{@link DriftKind#RUNTIME_DELETED}: full snapshot row</li>
     *   <li>{@link DriftKind#RUNTIME_MODIFIED}: only the diverged fields, snapshot values</li>
     * </ul>
     */
    @Schema(description = "Snapshot side; null when snapshot has no row for this id")
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

    @Schema(description = "ISO datetime carried by whichever side owns the row "
            + "(snapshot for RUNTIME_DELETED/MODIFIED, runtime for RUNTIME_ADDED)")
    private String lastChangedTime;
}
