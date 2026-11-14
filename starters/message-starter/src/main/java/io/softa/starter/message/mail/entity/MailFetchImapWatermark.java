package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * IMAP watermark of a tenant's own receive server. Multi-tenant. Moved by the fetch loop only, so it keeps
 * no change log.
 */
@Data
@Model(
        label = "Mail Fetch IMAP Watermark",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"serverConfigId", "folderName"},
        versionLock = true,
        multiTenant = true,
        enableAuditLog = false
)
@Index(indexName = "uk_config_folder", fields = {"serverConfigId", "folderName"}, unique = true)
@Index(indexName = "idx_watermark_tenant", fields = {"tenantId"})
@EqualsAndHashCode(callSuper = true)
public class MailFetchImapWatermark extends AbstractMailFetchWatermark {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
