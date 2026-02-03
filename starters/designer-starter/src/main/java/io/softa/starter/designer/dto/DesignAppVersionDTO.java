package io.softa.starter.designer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * DesignAppVersionDTO for creating a new version
 */
@Data
@Schema(name = "DesignAppVersionDTO", description = "DesignAppVersionDTO for creating a new version")
public class DesignAppVersionDTO {

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Env ID")
    private Long envId;

    @Schema(description = "Version Name")
    private String name;

    @Schema(description = "Upgrade description")
    private String description;

}