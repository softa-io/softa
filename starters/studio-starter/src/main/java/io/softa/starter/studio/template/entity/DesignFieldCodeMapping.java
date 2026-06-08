package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignFieldCodeMapping Model
 */
@Data
@Model(label = "Design Field Code Mapping", idStrategy = IdStrategy.DISTRIBUTED_LONG)
@EqualsAndHashCode(callSuper = true)
public class DesignFieldCodeMapping extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Code Lang", required = true)
    private DesignCodeLang codeLang;

    @Field(label = "Field Type", required = true)
    private FieldType fieldType;

    @Field(label = "Property Type", required = true, length = 64)
    private String propertyType;

    @Field(label = "Description", length = 256)
    private String description;

    @Field(label = "Deleted")
    private Boolean deleted;
}
