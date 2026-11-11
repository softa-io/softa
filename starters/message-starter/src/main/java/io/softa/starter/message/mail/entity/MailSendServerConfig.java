package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * A tenant's own outgoing mail server. Multi-tenant: each tenant sees and
 * manages only its own rows. A tenant without an enabled default here has its
 * mail relayed through the platform's default send server
 * ({@link PlatformMailSendServer}).
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true, activeControl = true, defaultOrder = {"sequence", "name"})
@Index(indexName = "idx_mail_send_cfg_default", fields = {"tenantId", "isDefault"})
@EqualsAndHashCode(callSuper = true)
public class MailSendServerConfig extends AbstractMailSendServer {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
