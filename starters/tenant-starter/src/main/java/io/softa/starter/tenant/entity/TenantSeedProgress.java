package io.softa.starter.tenant.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.tenant.enums.SeederStatus;

/**
 * Per-tenant, per-seeder completion ledger — the idempotent registry behind provisioning-status
 * aggregation. One row per {@code (tenantId, seederKey)}. Shared (non-multiTenant): {@code tenantId} is a
 * plain column, written / read in the system context by the coordinator, matching {@link TenantInfo} /
 * {@link TenantSubscription}.
 *
 * <p>{@code seederKey} is opaque — the framework never interprets it; business modules assign it
 * ("pre-data", "corehr", …), and it must match the app's {@code softa.tenant.provisioning.expected-seeders}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG)
@Index(indexName = "uk_tenant_seed_progress", fields = {"tenantId", "seederKey"}, unique = true)
public class TenantSeedProgress extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Seeder Key")
    private String seederKey;

    @Field
    private SeederStatus status;
}
