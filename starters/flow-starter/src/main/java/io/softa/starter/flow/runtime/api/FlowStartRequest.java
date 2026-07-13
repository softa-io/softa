package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for starting an execution from a registered flow bundle.
 * <p>
 * Resolution priority:
 * <ol>
 *   <li>{@code bundleId} — start this exact published revision (pinned).</li>
 *   <li>{@code designId} — start the current (active) revision of a flow design.</li>
 * </ol>
 * {@code designId} is the {@link io.softa.starter.flow.entity.FlowDesign#getId()} distributed ID,
 * which is globally unique and tenant-collision-free. To start a specific historical revision,
 * look up its {@code bundleId} via the revisions listing endpoint and pass that instead.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowStartRequest")
public class FlowStartRequest extends AbstractFlowLaunchContextRequest {

    @Schema(description = "Flow design id (stable flow identity, globally unique)")
    private Long designId;

    @Schema(description = "Optional: pinned bundle id; if set, designId is ignored")
    private Long bundleId;

    @Schema(description = "Instance title for display and search")
    private String title;

    @Schema(description = "Related model name for SQL-level indexing")
    private String modelName;

    @Schema(description = "Related row data ID for SQL-level indexing")
    private String rowId;
}
