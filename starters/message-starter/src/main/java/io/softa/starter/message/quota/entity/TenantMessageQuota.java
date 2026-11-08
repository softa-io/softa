package io.softa.starter.message.quota.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Monthly send-volume quota for one tenant — a commercial ceiling, not rate
 * limiting. A send is charged when its record is prepared (its first delivery
 * attempt); once the month's charged sends reach the limit, further records
 * fail with {@code MONTHLY_QUOTA_EXCEEDED}, logged at ERROR — raise the limit
 * and Retry them. Complements — never replaces — the per-config infrastructure
 * limits ({@code dailySendLimit} / {@code rateLimitPerMinute}), which protect
 * individual servers/providers at delivery time.
 * <p>
 * Deliberately NOT {@code multiTenant}: this is a platform-owned registry
 * ABOUT tenants, managed by platform operations only — a tenant must never
 * see or edit its own ceiling. The governed tenant is a plain column;
 * {@code tenantId = -1} governs the platform's own sends (the platform quota
 * is expected to be very large — it exists to cap runaway or malicious mass
 * sending, not day-to-day volume).
 * <p>
 * A missing row (or a null limit) falls back to the deployment defaults in
 * {@code message.quota.*}; a null resolved limit means unlimited.
 */
@Data
@Model(label = "Tenant Message Quota", idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"tenantId"})
@Index(indexName = "uk_tenant_message_quota_tenant", fields = {"tenantId"}, unique = true,
        message = "A quota row for this tenant already exists.")
@EqualsAndHashCode(callSuper = true)
public class TenantMessageQuota extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true,
            description = "The governed tenant; -1 = the platform's own quota. Plain column — this "
                    + "model is platform-owned and not tenant-isolated.")
    private Long tenantId;

    @Field(label = "Mail Monthly Limit",
            description = "Maximum mail sends charged per calendar month; a send is charged when its record "
                    + "is prepared, at its first delivery attempt. "
                    + "Null = use the deployment default (softa.message.quota.mail-monthly-default; "
                    + "null there = unlimited).")
    private Long mailMonthlyLimit;

    @Field(label = "SMS Monthly Limit",
            description = "Maximum SMS sends charged per calendar month; a send is charged when its record "
                    + "is prepared, at its first delivery attempt. "
                    + "Null = use the deployment default (softa.message.quota.sms-monthly-default; "
                    + "null there = unlimited).")
    private Long smsMonthlyLimit;

    @Field(length = 500, description = "Operations note, e.g. the plan or contract behind this ceiling")
    private String description;
}
