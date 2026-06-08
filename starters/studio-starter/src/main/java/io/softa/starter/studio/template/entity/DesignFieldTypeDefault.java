package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * DesignFieldTypeDefault Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Design Field Type Default",
        idStrategy = IdStrategy.DISTRIBUTED_LONG
)
public class DesignFieldTypeDefault extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Field Type", required = true)
    private FieldType fieldType;

    @Field(label = "Default Value", length = 64)
    private String defaultValue;

    @Field(label = "Length")
    private Integer length;

    @Field(label = "Scale")
    private Integer scale;

    @Field(label = "Deleted")
    private Boolean deleted;
}
