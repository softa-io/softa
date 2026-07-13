package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SMS template.
 * <p>
 * Templates are identified by {@code code}. Resolution prefers a tenant
 * template, falling back to the platform template:
 * <ol>
 *   <li>Tenant template for {@code code}</li>
 *   <li>Platform template (tenant_id = 0) for {@code code}</li>
 * </ol>
 * <p>
 * Content supports {@code {{ variable }}} placeholders rendered by
 * {@link io.softa.framework.base.placeholder.PlaceholderUtils}.
 * <p>
 * tenant_id = 0  — platform-level, shared across all tenants.
 * tenant_id > 0  — tenant-level, ORM auto-fills and isolates.
 */
@Data
@Model(label = "SMS Template", idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "uk_sms_template_tenant_code", fields = {"tenantId", "code"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class SmsTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(required = true, length = 100,
            description = "Unique template code used for programmatic lookup, e.g. VERIFY_CODE")
    private String code;

    @Field(required = true, length = 100)
    private String name;

    @Field(length = 500)
    private String description;

    @Field(length = 65535,
            description = "SMS body template with {{ variable }} placeholders")
    private String content;

    @Field(label = "Enabled", description = "Whether this template is active")
    private Boolean isEnabled;
}
