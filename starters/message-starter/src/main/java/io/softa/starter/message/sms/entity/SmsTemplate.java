package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SMS template.
 * <p>
 * Templates are identified by {@code code}, unique across the table.
 * <p>
 * Platform-owned: SMS is a platform service, so each template exists once, for
 * every tenant. The table is not multi-tenant and has no tenant column; the
 * tenant a send is made for decides whom its record is filed under and whose
 * monthly quota it draws on, never which template it renders.
 * <p>
 * Content supports {@code {{ variable }}} placeholders rendered by
 * {@link io.softa.framework.base.placeholder.PlaceholderUtils}.
 */
@Data
@Model(label = "SMS Template", idStrategy = IdStrategy.DISTRIBUTED_LONG,
        activeControl = true,
        defaultOrder = {"name"})
@Index(indexName = "uk_sms_template_code", fields = {"code"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class SmsTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true, length = 100,
            description = "Unique template code used for programmatic lookup, e.g. VERIFY_CODE")
    private String code;

    @Field(required = true, length = 100)
    private String name;

    @Field(length = 500)
    private String description;

    @Field(fieldType = FieldType.TEXT,
            description = "SMS body template with {{ variable }} placeholders")
    private String content;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true},
     * so a disabled row is retired from every list and every resolution without
     * being deleted (rows referenced by send records stay intact). Filter on
     * {@code active} explicitly to see disabled rows.
     */
    @Field(renamedFrom = "isEnabled")
    private Boolean active;
}
