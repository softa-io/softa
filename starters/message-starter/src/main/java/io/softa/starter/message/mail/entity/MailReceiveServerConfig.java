package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * A tenant's own incoming mail server. Multi-tenant: each tenant sees and
 * manages only its own rows; what arrives through it is stored as that
 * tenant's {@link MailReceiveRecord}. A tenant without one receives nothing —
 * never the platform's mailbox.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true, activeControl = true, defaultOrder = {"sequence", "name"})
@Index(indexName = "idx_mail_recv_cfg_default", fields = {"tenantId", "isDefault"})
@EqualsAndHashCode(callSuper = true)
public class MailReceiveServerConfig extends AbstractMailReceiveServer {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
