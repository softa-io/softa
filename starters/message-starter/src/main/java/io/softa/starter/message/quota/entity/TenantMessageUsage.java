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
 * One quota bucket's send ledger for one calendar month — the durable,
 * queryable history behind the monthly quota: charged send counts per
 * channel plus a snapshot of the ceiling that was in force. Rows accumulate
 * forever (one per bucket per month), so per-tenant consumption history is a
 * plain model query; remaining allowance is derived
 * ({@code limit - used}; a null limit means unlimited).
 * <p>
 * Deliberately NOT {@code multiTenant} — like {@code TenantMessageQuota},
 * this is a platform-owned ledger ABOUT tenants ({@code tenantId = -1} is the
 * platform's own bucket). <b>System-maintained</b>: rows are created and
 * incremented when a send record is prepared, via optimistic-lock CAS
 * ({@code versionLock}); manual edits distort the accounting and the
 * enforcement it feeds. The {@code *MonthlyLimit} columns are per-send
 * snapshots of the resolved ceiling ({@code TenantMessageQuota} row or
 * deployment default) — historical rows therefore show the limit that
 * governed that month, while enforcement always compares against the
 * CURRENT resolved limit (an operations change takes effect immediately).
 */
@Data
@Model(label = "Tenant Message Usage", idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"tenantId", "month"}, versionLock = true, copyable = false)
@Index(indexName = "uk_tenant_message_usage_bucket", fields = {"tenantId", "month"}, unique = true,
        message = "A usage row for this tenant and month already exists.")
@EqualsAndHashCode(callSuper = true)
public class TenantMessageUsage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true,
            description = "The quota bucket; -1 = the platform's own sends.")
    private Long tenantId;

    @Field(required = true, length = 7,
            description = "Calendar month of this ledger row, format yyyy-MM (server default zone).")
    private String month;

    @Field(label = "Mail Monthly Limit",
            description = "Snapshot of the mail ceiling in force at the last accepted mail send "
                    + "of this month (quota row or deployment default); null = unlimited.")
    private Long mailMonthlyLimit;

    @Field(label = "Mail Used", required = true,
            description = "Accepted mail sends this month. Incremented once per accepted message; "
                    + "delivery retries never touch it.")
    private Long mailUsed;

    @Field(label = "SMS Monthly Limit",
            description = "Snapshot of the SMS ceiling in force at the last accepted SMS send "
                    + "of this month; null = unlimited.")
    private Long smsMonthlyLimit;

    @Field(label = "SMS Used", required = true,
            description = "Accepted SMS sends this month. Incremented once per accepted message; "
                    + "delivery retries never touch it.")
    private Long smsUsed;

    @Field(required = true, description = "Optimistic-lock version. Bumped on every increment.")
    private Long version;
}
