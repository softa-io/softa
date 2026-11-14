package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Mail the platform's receive server fetched. Not multi-tenant and shown only
 * on platform menus. Its bounces and read receipts mark the send records the
 * platform's send servers carried — tenants' relayed mail included, so a
 * tenant sees in its send log that a relayed mail bounced while the bounce
 * itself stays with the platform, which watches the bounce rate of its own
 * servers.
 */
@Data
@Model(
        label = "Platform Mail Receive Record",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"serverConfigId", "messageId"},
        copyable = false
)
@Index(indexName = "uk_platform_mail_recv_msg", fields = {"serverConfigId", "messageId"}, unique = true)
@Index(indexName = "idx_platform_mail_recv_status", fields = {"status"})
@Index(indexName = "idx_platform_mail_recv_trunc", fields = {"truncationReason"})
@EqualsAndHashCode(callSuper = true)
public class PlatformMailReceiveRecord extends AbstractMailReceiveRecord {

    @Serial
    private static final long serialVersionUID = 1L;
}
