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
 * Send-volume quota for one tenant — commercial ceilings, not rate limiting:
 * a monthly allowance, and a daily cap so that one tenant's burst cannot take
 * a shared platform server's or provider's whole day. A send is charged to both
 * when its record is prepared (its first delivery attempt); once the day's or
 * the month's charged sends reach the limit, further records fail with
 * {@code DAILY_QUOTA_EXCEEDED} / {@code MONTHLY_QUOTA_EXCEEDED}, logged at
 * ERROR — raise the limit (or wait for the next day) and Retry them.
 * Complements — never replaces — the per-config infrastructure limits
 * ({@code dailySendLimit} / {@code rateLimitPerMinute}), which protect each
 * server/provider at delivery time and count every tenant together.
 * <p>
 * Deliberately NOT {@code multiTenant}: this is a platform-owned registry
 * ABOUT tenants, managed by platform operations only — a tenant must never
 * see or edit its own ceiling. The governed tenant is a plain column;
 * {@code tenantId = -1} governs the sends no tenant can be named for (the
 * platform's limits are expected to be very large — they exist to cap runaway
 * or malicious mass sending, not day-to-day volume).
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

    @Field(label = "Mail Daily Limit",
            description = "Maximum mail sends charged per calendar day, counted with the monthly limit when a "
                    + "record is prepared — so one tenant cannot take a shared platform server's whole day. "
                    + "Null = use the deployment default (softa.message.quota.mail-daily-default; "
                    + "null there = unlimited).")
    private Long mailDailyLimit;

    @Field(label = "SMS Daily Limit",
            description = "Maximum SMS sends charged per calendar day, counted with the monthly limit when a "
                    + "record is prepared — so one tenant cannot take a shared provider's whole day. "
                    + "Null = use the deployment default (softa.message.quota.sms-daily-default; "
                    + "null there = unlimited).")
    private Long smsDailyLimit;

    @Field(length = 500, description = "Operations note, e.g. the plan or contract behind this ceiling")
    private String description;
}
