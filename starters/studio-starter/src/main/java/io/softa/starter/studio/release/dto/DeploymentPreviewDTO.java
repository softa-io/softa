package io.softa.starter.studio.release.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * DTO for previewing a deployment's content.
 * Contains the merged changes, DDL, and preflight check results.
 */
@Data
@Schema(name = "DeploymentPreviewDTO")
public class DeploymentPreviewDTO {

    @Schema(description = "Merged changes from all versions in the deployment")
    private List<ModelChangesDTO> mergedChanges;

    @Schema(description = "Merged table DDL")
    private String ddlTable;

    @Schema(description = "Merged index DDL")
    private String ddlIndex;

    @Schema(description = "Diff hash of the merged content")
    private String diffHash;

    @Schema(description = "Preflight warnings")
    private List<String> preflightWarnings;

}

