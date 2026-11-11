package io.softa.starter.message.mail.support;

import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.shared.ConfigCache;

/**
 * Read-through cache for the platform's {@link PlatformMailSendServer} lookups;
 * its one default is keyed {@code -1}. See {@link ConfigCache} for semantics.
 */
@Component
public class PlatformMailConfigCache extends ConfigCache<PlatformMailSendServer> {

    public PlatformMailConfigCache() {
        super("mail:platform-send-server:id:", "mail:platform-send-server:default:", PlatformMailSendServer.class);
    }
}
