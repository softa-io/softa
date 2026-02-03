package io.softa.starter.designer.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * DesignFieldTypeMapping Model
 */
@Data
@Schema(name = "DesignFieldTypeMapping")
@EqualsAndHashCode(callSuper = true)
public class DesignFieldTypeMapping extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Database Type")
    private DatabaseType databaseType;

    @Schema(description = "Field Type")
    private FieldType fieldType;

    @Schema(description = "Column Type")
    private String columnType;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}