package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * The platform's own email template — account credentials, security notices,
 * the platform's contract reminders — resolved by {@code scope = PLATFORM}
 * sends. Not multi-tenant and managed only from platform menus: tenants can
 * neither read nor change it, and the code list here is the list of platform
 * codes a tenant template may not take.
 */
@Data
@Model(
        label = "Platform Mail Template",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"code"},
        activeControl = true,
        defaultOrder = {"name"}
)
@Index(indexName = "uk_platform_mail_template_code", fields = {"code"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class PlatformMailTemplate extends AbstractMailTemplate {

    @Serial
    private static final long serialVersionUID = 1L;
}
