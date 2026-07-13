package io.softa.starter.studio.release.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.release.enums.DesignAppStatus;

/**
 * DesignApp Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, displayName = "appName")
public class DesignApp extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Owner")
    private Long ownerId;

    @Field(required = true)
    private String appName;

    @Field(required = true)
    private String appCode;

    @Field
    private String appType;

    @Field(description = "Fill in when you need to generate code, the model in the same App belongs to the same Module.")
    private String packageName;

    @Field
    private DesignAppStatus appStatus;

    @Field(length = 256)
    private String description;
}
