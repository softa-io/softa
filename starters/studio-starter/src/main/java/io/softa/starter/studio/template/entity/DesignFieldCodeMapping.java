package io.softa.starter.studio.template.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignFieldCodeMapping Model
 */
@Data
@Schema(name = "DesignFieldCodeMapping")
@EqualsAndHashCode(callSuper = true)
public class DesignFieldCodeMapping extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Code Language")
    private DesignCodeLang codeLang;

    @Schema(description = "Field Type")
    private FieldType fieldType;

    @Schema(description = "Property Type Template")
    private String propertyType;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
