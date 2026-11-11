package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * IMAP watermark of the platform's own receive server. Not multi-tenant.
 */
@Data
@Model(
        label = "Platform Mail Fetch Watermark",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"serverConfigId", "folderName"},
        versionLock = true
)
@Index(indexName = "uk_platform_mail_watermark", fields = {"serverConfigId", "folderName"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class PlatformMailFetchWatermark extends AbstractMailFetchWatermark {

    @Serial
    private static final long serialVersionUID = 1L;
}
