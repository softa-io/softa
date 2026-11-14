package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * A tenant's own email template, resolved by {@code scope = TENANT} sends.
 * Multi-tenant: tenants receive their rows at provisioning from the
 * application's per-tenant seed files and edit them freely from then on. A
 * code the platform already uses for one of its {@link PlatformMailTemplate}s
 * is refused — one code, one tier.
 */
@Data
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"code"},
        multiTenant = true,
        activeControl = true,
        defaultOrder = {"name"}
)
@Index(indexName = "uk_mail_template_tenant_code", fields = {"tenantId", "code"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class MailTemplate extends AbstractMailTemplate {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
