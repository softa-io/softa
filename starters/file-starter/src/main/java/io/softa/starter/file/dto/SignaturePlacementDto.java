package io.softa.starter.file.dto;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SignaturePlacementDto")
public record SignaturePlacementDto(
        @Schema(description = "Page number, 1-based. 0 will be treated as page 1.") Integer page,
        @Schema(description = "Left X coordinate.") BigDecimal x,
        @Schema(description = "Bottom Y coordinate.") BigDecimal y,
        @Schema(description = "Signature box width.") BigDecimal width,
        @Schema(description = "Signature box height.") BigDecimal height,
        @Schema(description = "Coordinate unit: PT, PX, MM, CM, IN.") String unit
) {
}
