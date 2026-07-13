package io.softa.starter.studio.release.entity;

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

    @Field(required = true)
    private DatabaseType databaseType;

    @Field(required = true)
    private FieldType fieldType;

    @Field(required = true)
    private String columnType;

    @Field(length = 256)
    private String description;
}
