package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.WidgetType;

/**
 * A named, reusable, dbType-agnostic field <b>domain</b>: packages a logical field shape
 * — {@code fieldType} + {@code length}/{@code scale}/{@code defaultValue} (data/physical) and
 * {@code widgetType} (UI) — under a {@code name}, so many {@code DesignField}s can reference one
 * definition ({@code DesignField.domainId}) and override per-attr.
 *
 * <p>Generalizes the retired per-FieldType default (the role moved to builtin Code): a domain is
 * <b>named and may be one-of-many per FieldType</b>, not a 1:1 FieldType singleton. {@code widgetType} is
 * Softa's superset over a pure data modeler's domain (Softa fields drive both DDL and UI); dbType-agnostic
 * — JDBC / pure-data targets read only the type part and ignore {@code widgetType}.
 *
 * <p>Renamed from {@code DesignFieldTypeDefault} ({@code @Model(renamedFrom)}): the scanner does
 * {@code ALTER TABLE design_field_type_default RENAME TO design_field_domain} (data carried, id preserved).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        businessKey = {"name"},
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        renamedFrom = "DesignFieldTypeDefault"
)
public class DesignFieldDomain extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true, description = "Domain name — the business key fields reference")
    private String name;

    @Field(required = true)
    private FieldType fieldType;

    @Field(description = "UI widget hint (dbType-agnostic; pure-data / JDBC targets ignore it)")
    private WidgetType widgetType;

    @Field
    private String defaultValue;

    @Field
    private Integer length;

    @Field
    private Integer scale;
}
