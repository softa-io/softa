package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysPreData Model
 * A binding row is scoped by tenantId: null = system scope (data-system seeds),
 * non-null = that tenant's scope (data-tenant seeds). The model itself stays
 * non-multi-tenant so one table serves both scopes and stays globally visible.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "System Predefined Data")
@Index(fields = {"model", "tenantId", "preId"}, unique = true)
public class SysPreData extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String model;

    @Field(label = "Pre ID", required = true)
    private String preId;

    @Field(label = "Row ID")
    private String rowId;

    @Field
    private Boolean frozen;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
