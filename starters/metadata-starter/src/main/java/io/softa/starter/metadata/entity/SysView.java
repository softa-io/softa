package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.ViewType;

/**
 * SysView Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "System View", businessKey = {"modelName", "code"}, defaultOrder = {"modelName", "sequence"})
public class SysView extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    @Field
    private String modelName;

    @Field(required = true)
    private String name;

    @Field
    private String code;

    @Field(required = true)
    private ViewType type;

    @Field
    private Integer sequence;

    @Field(required = true)
    private JsonNode structure;

    @Field
    private Filters defaultFilter;

    @Field
    private Orders defaultOrder;

    @Field(label = "Nav ID")
    private Long navId;

    @Field
    private Boolean publicView;

    @Field
    private Boolean defaultView;

    @Field
    private Boolean deleted;
}
