package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * The platform's own outgoing mail server. Not multi-tenant and managed only
 * from platform menus: it carries the platform's mail ({@code PLATFORM}
 * scope), and relays a tenant's mail when the tenant has no send server of
 * its own. Tenants never see it.
 */
@Data
@Model(label = "Platform Mail Send Server", idStrategy = IdStrategy.DISTRIBUTED_LONG, activeControl = true,
        defaultOrder = {"sequence", "name"})
@Index(indexName = "idx_platform_mail_send_default", fields = {"isDefault"})
@EqualsAndHashCode(callSuper = true)
public class PlatformMailSendServer extends AbstractMailSendServer {

    @Serial
    private static final long serialVersionUID = 1L;
}
