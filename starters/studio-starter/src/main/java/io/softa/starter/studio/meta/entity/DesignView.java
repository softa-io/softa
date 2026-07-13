package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.ViewType;

/**
 * DesignView Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG,
        // hard delete (no softDelete) so a re-created view never collides with a soft-deleted row that
        // still occupies the {modelName, code} business key — consistent with the other design_* models.
        businessKey = {"modelName", "code"})
public class DesignView extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID")
    private Long appId;

    @Field
    private String modelName;

    @Field(label = "View Name", required = true)
    private String name;

    @Field(label = "View Code")
    private String code;

    @Field(label = "View Type", required = true)
    private ViewType type;

    @Field(required = true)
    private Integer sequence;

    @Field(required = true)
    private JsonNode structure;

    @Field(label = "Default Filters", description = "View level default filter.")
    private JsonNode defaultFilter;

    @Field(description = "The default sorting condition at the view level.")
    private JsonNode defaultOrder;

    @Field(label = "Navigation ID")
    private Long navId;

    @Field
    private Boolean publicView;

    @Field
    private Boolean defaultView;
}
