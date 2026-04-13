package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DesignOptionSet Model
 */
@Data
@Schema(name = "DesignOptionSet")
@EqualsAndHashCode(callSuper = true)
public class DesignOptionSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Option Set Name")
    private String name;

    @Schema(description = "Option Set Code")
    private String optionSetCode;

    @Schema(description = "Option Items")
    private List<DesignOptionItem> optionItems;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}