package io.softa.starter.studio.template.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;

/**
 * DesignSqlTemplate Model
 */
@Data
@Schema(name = "DesignSqlTemplate")
@EqualsAndHashCode(callSuper = true)
public class DesignSqlTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Database Type")
    private DatabaseType databaseType;

    @Schema(description = "Create Table Template")
    private String createTableTemplate;

    @Schema(description = "Alter Index Template")
    private String alterIndexTemplate;

    @Schema(description = "Alter Table Template")
    private String alterTableTemplate;

    @Schema(description = "Drop Table Template")
    private String dropTableTemplate;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
