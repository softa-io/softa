package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * DesignSqlTemplate Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Design SQL Template",
        idStrategy = IdStrategy.DISTRIBUTED_LONG
)
public class DesignSqlTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Database Type", required = true)
    private DatabaseType databaseType;

    @Field(label = "Create Table Template", required = true, length = 20000)
    private String createTableTemplate;

    @Field(label = "Alter Index Template", required = true, length = 20000)
    private String alterIndexTemplate;

    @Field(label = "Alter Table Template", required = true, length = 20000)
    private String alterTableTemplate;

    @Field(label = "Drop Table Template", required = true, length = 20000)
    private String dropTableTemplate;

    @Field(label = "Description", length = 256)
    private String description;

    @Field(label = "Deleted")
    private Boolean deleted;
}
