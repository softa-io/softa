package io.softa.starter.message.mail.support;

import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.shared.ConfigCache;

/**
 * Read-through cache for {@link MailSendServerConfig} lookups.
 * See {@link ConfigCache} for semantics; this subclass only binds the
 * cache keys and entity type for the mail channel.
 */
@Component
public class MailConfigCache extends ConfigCache<MailSendServerConfig> {

    public MailConfigCache() {
        super("mail:send-config:id:", "mail:send-config:default:tenant:", MailSendServerConfig.class);
    }
}
