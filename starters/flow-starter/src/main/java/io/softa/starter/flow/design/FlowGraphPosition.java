package io.softa.starter.flow.design;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Position of a node on the design canvas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowGraphPosition")
public class FlowGraphPosition {

    @Schema(description = "X coordinate")
    private Double x;

    @Schema(description = "Y coordinate")
    private Double y;
}

