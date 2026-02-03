package io.softa.starter.designer.entity;

import tools.jackson.databind.JsonNode;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * DesignAppEnvMerge Model
 */
@Data
@Schema(name = "DesignAppEnvMerge")
@EqualsAndHashCode(callSuper = true)
public class DesignAppEnvMerge extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Source Env ID")
    private Long sourceEnvId;

    @Schema(description = "Target Env ID")
    private Long targetEnvId;

    @Schema(description = "Merge description")
    private String description;

    @Schema(description = "Merge Content")
    private JsonNode mergeContent;

    @Schema(description = "Deleted")
    private Boolean deleted;
}