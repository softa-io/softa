package io.softa.starter.metadata.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.OptionItemIcon;
import io.softa.framework.orm.enums.OptionItemTone;

/**
 * SysOptionItem Model
 */
@Data
@Schema(name = "SysOptionItem")
@EqualsAndHashCode(callSuper = true)
public class SysOptionItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

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

    @Schema(description = "Item Tone")
    private OptionItemTone itemTone;

    @Schema(description = "Item Icon")
    private OptionItemIcon itemIcon;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Active")
    private Boolean active;
}