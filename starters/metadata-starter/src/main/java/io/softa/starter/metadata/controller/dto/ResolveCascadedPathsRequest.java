package io.softa.starter.metadata.controller.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "ResolveCascadedPathsRequest")
public class ResolveCascadedPathsRequest {

    @Schema(description = "Root model name; the first segment of every path must be a field on this model.",
            example = "AppEnv", requiredMode = Schema.RequiredMode.REQUIRED)
    private String rootModel;

    @Schema(description = "Cascaded paths to resolve, dot-separated.",
            example = "[\"lastDeploymentId.deployStatus\", \"ownerId.departmentId.name\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> paths;
}
