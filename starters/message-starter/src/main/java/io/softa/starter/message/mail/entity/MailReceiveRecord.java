package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Mail a tenant's own receive server fetched. Multi-tenant: each tenant sees
 * only its own. Its bounces and read receipts mark the tenant's own send
 * records — those its own send servers carried.
 */
@Data
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"serverConfigId", "messageId"},
        copyable = false,
        multiTenant = true
)
@Index(indexName = "uk_server_msg", fields = {"serverConfigId", "messageId"}, unique = true)
@Index(indexName = "idx_mail_recv_tenant_status", fields = {"tenantId", "status"})
@Index(indexName = "idx_truncation", fields = {"truncationReason"})
@EqualsAndHashCode(callSuper = true)
public class MailReceiveRecord extends AbstractMailReceiveRecord {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
