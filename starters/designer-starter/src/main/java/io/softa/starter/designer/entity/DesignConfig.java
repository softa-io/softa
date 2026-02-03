package io.softa.starter.designer.entity;

import tools.jackson.databind.JsonNode;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * DesignConfig Model
 */
@Data
@Schema(name = "DesignConfig")
@EqualsAndHashCode(callSuper = true)
public class DesignConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "External ID")
    private Long externalId;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Env ID")
    private Long envId;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Code")
    private String code;

    @Schema(description = "Value")
    private JsonNode value;

    @Schema(description = "Value Data Type")
    private String valueType;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}