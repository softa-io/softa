package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.OptionItemColor;

/**
 * DesignOptionItem Model
 */
@Data
@Schema(name = "DesignOptionItem")
@EqualsAndHashCode(callSuper = true)
public class DesignOptionItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Option Set ID")
    private Long optionSetId;

    @Schema(description = "Option Set Code")
    private String optionSetCode;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Item Code")
    private String itemCode;

    @Schema(description = "Item Name")
    private String itemName;

    @Schema(description = "Parent Item Code")
    private String parentItemCode;

    @Schema(description = "Item Color")
    private OptionItemColor itemColor;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Active")
    private Boolean active;

    @Schema(description = "Deleted")
    private Boolean deleted;
}