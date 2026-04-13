package io.softa.starter.studio.release.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "WorkItemVersionDTO", description = "Request for associating a WorkItem with a Version")
public class WorkItemVersionDTO {

    @Schema(description = "WorkItem ID")
    private Long workItemId;

    @Schema(description = "Version ID")
    private Long versionId;

}
