package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * DesignFieldDbMapping Model
 * Mapping between the FieldType and the column type of the database.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Design Field DB Mapping",
        description = "Mapping between the FieldType and the column type of the database.",
        idStrategy = IdStrategy.DISTRIBUTED_LONG
)
public class DesignFieldDbMapping extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Database Type", required = true)
    private DatabaseType databaseType;

    @Field(label = "Field Type", required = true)
    private FieldType fieldType;

    @Field(label = "Column Type", required = true, length = 64)
    private String columnType;

    @Field(label = "Description", length = 256)
    private String description;

    @Field(label = "Deleted")
    private Boolean deleted;
}
