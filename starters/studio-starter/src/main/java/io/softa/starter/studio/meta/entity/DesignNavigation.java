package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DesignNavigation Model
 */
@Data
@Schema(name = "DesignNavigation")
@EqualsAndHashCode(callSuper = true)
public class DesignNavigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Type")
    private String type;

    @Schema(description = "Code")
    private String code;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "Parent Navigation")
    private Long parentId;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Default filters")
    private String filter;

    @Schema(description = "Deleted")
    private Boolean deleted;
}