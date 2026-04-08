package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.ViewType;

/**
 * DesignView Model
 */
@Data
@Schema(name = "DesignView")
@EqualsAndHashCode(callSuper = true)
public class DesignView extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "View Name")
    private String name;

    @Schema(description = "View Code")
    private String code;

    @Schema(description = "View Type")
    private ViewType type;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Structure")
    private JsonNode structure;

    @Schema(description = "Default Filters")
    private JsonNode defaultFilter;

    @Schema(description = "Default Order")
    private JsonNode defaultOrder;

    @Schema(description = "Navigation ID")
    private Long navId;

    @Schema(description = "Public View")
    private Boolean publicView;

    @Schema(description = "Default View")
    private Boolean defaultView;

    @Schema(description = "Deleted")
    private Boolean deleted;
}