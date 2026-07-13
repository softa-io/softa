package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * DesignNavigation Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        // hard delete (no softDelete) so a re-created navigation never collides with a soft-deleted row
        // that still occupies the {code} business key — consistent with the other design_* models.
        businessKey = {"code"}
)
public class DesignNavigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID")
    private Long appId;

    @Field(required = true)
    private String name;

    @Field(required = true)
    private String type;

    @Field(required = true)
    private String code;

    @Field(length = 256)
    private String modelName;

    @Field(label = "Parent Navigation")
    private Long parentId;

    @Field(length = 256)
    private String description;

    @Field(label = "Default filters", description = "The default filters at the menu level.", length = 256)
    private String filter;
}
