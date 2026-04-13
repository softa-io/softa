package io.softa.starter.studio.template.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;

/**
 * DesignFieldTypeDefault Model
 */
@Data
@Schema(name = "DesignFieldTypeDefault")
@EqualsAndHashCode(callSuper = true)
public class DesignFieldTypeDefault extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Field Type")
    private FieldType fieldType;

    @Schema(description = "Default Value")
    private String defaultValue;

    @Schema(description = "Length")
    private Integer length;

    @Schema(description = "Scale")
    private Integer scale;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
