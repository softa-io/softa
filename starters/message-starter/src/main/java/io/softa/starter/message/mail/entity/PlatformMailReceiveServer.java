package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * The platform's own incoming mail server. Not multi-tenant and managed only
 * from platform menus; what arrives through it — bounces and receipts of the
 * mail the platform's send servers carried, tenants' relayed mail included —
 * is stored as {@link PlatformMailReceiveRecord}.
 */
@Data
@Model(label = "Platform Mail Receive Server", idStrategy = IdStrategy.DISTRIBUTED_LONG, activeControl = true,
        defaultOrder = {"sequence", "name"})
@Index(indexName = "idx_platform_mail_recv_default", fields = {"isDefault"})
@EqualsAndHashCode(callSuper = true)
public class PlatformMailReceiveServer extends AbstractMailReceiveServer {

    @Serial
    private static final long serialVersionUID = 1L;
}
