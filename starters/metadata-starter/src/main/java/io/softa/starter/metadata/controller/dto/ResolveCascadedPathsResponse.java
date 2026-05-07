package io.softa.starter.metadata.controller.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Schema(name = "ResolveCascadedPathsResponse")
public class ResolveCascadedPathsResponse {

    @Schema(description = "Related metaModels reachable from successful paths. "
            + "The root model is excluded because the caller already has it.")
    private List<MetaModelDTO> metaModels;

    @Schema(description = "Per-path resolution result, in request order.")
    private List<PathResolution> resolutions;

    public ResolveCascadedPathsResponse(List<MetaModelDTO> metaModels, List<PathResolution> resolutions) {
        this.metaModels = metaModels;
        this.resolutions = resolutions;
    }
}
