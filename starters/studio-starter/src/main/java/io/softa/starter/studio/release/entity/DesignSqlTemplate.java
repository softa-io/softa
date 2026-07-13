package io.softa.starter.studio.release.entity;

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

    @Field(required = true)
    private DatabaseType databaseType;

    @Field(required = true, length = 20000)
    private String createTableTemplate;

    @Field(required = true, length = 20000)
    private String alterIndexTemplate;

    @Field(required = true, length = 20000)
    private String alterTableTemplate;

    @Field(required = true, length = 20000)
    private String dropTableTemplate;

    @Field(length = 256)
    private String description;
}
