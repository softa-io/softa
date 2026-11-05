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
 * Templates are identified by {@code code}. Resolution prefers a tenant
 * template, falling back to the platform template:
 * <ol>
 *   <li>{@code scope = TENANT}: the current tenant's template only (rows
 *       arrive at provisioning via the application's per-tenant seed files)</li>
 *   <li>{@code scope = PLATFORM}: the platform tier (tenant_id = -1) only</li>
 * </ol>
 * No overlay or fallback between the tiers.
 * <p>
 * Content supports {@code {{ variable }}} placeholders rendered by
 * {@link io.softa.framework.base.placeholder.PlaceholderUtils}.
 * <p>
 * tenant_id = -1 — platform-tier, owned by the platform operator,
 * invisible to tenant-scoped reads.
 * tenant_id > 0  — tenant-level, ORM auto-fills and isolates.
 */
@Data
@Model(label = "SMS Template", idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true,
        activeControl = true,
        defaultOrder = {"name"})
@Index(indexName = "uk_sms_template_tenant_code", fields = {"tenantId", "code"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class SmsTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "-1 = platform-tier (owned by the platform operator, invisible to tenants); "
                    + ">0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

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
