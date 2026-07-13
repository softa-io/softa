package io.softa.starter.flow.design;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canvas viewport metadata compatible with node editors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowGraphViewport")
public class FlowGraphViewport {

    @Schema(description = "Viewport x offset")
    private Double x;

    @Schema(description = "Viewport y offset")
    private Double y;

    @Schema(description = "Viewport zoom")
    private Double zoom;
}

