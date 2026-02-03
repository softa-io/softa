package io.softa.starter.designer.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * DesignOptionSetTrans Model
 */
@Data
@Schema(name = "DesignOptionSetTrans")
@EqualsAndHashCode(callSuper = true)
public class DesignOptionSetTrans extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Language Code")
    private String languageCode;

    @Schema(description = "Row ID")
    private Long rowId;

    @Schema(description = "Option Set Name")
    private String name;

    @Schema(description = "Description")
    private String description;
}