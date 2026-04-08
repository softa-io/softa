package io.softa.starter.studio.template.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;

/**
 * DesignFieldDbMapping Model
 * Mapping between the FieldType and the column type of the database.
 */
@Data
@Schema(name = "DesignFieldDbMapping")
@EqualsAndHashCode(callSuper = true)
public class DesignFieldDbMapping extends AuditableModel {

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
