package io.softa.starter.studio.release.dto;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-model drift report intended for UI consumption: a flat list of {@link DriftRowDTO}
 * grouped by their owning model. The order of {@code rows} preserves the underlying
 * iteration order from the diff (RUNTIME_DELETED, then RUNTIME_MODIFIED, then RUNTIME_ADDED),
 * which the frontend can re-sort freely.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DriftReport", description = "Drifted rows for a single model")
public class DriftReportDTO {

    @Schema(description = "Logical model name on the design side")
    private String model;

    @Schema(description = "All drifted rows for this model")
    @Builder.Default
    private List<DriftRowDTO> rows = new ArrayList<>();
}
