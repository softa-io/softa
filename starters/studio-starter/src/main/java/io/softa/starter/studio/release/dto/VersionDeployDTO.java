package io.softa.starter.studio.release.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "VersionDeployDTO", description = "Deployment request for deploying a Version to an Environment")
public class VersionDeployDTO {

    @Schema(description = "Version ID")
    private Long versionId;

    @Schema(description = "Target environment ID")
    private Long envId;

}
