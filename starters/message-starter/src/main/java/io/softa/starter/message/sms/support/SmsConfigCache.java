package io.softa.starter.message.sms.support;

import org.springframework.stereotype.Component;

import io.softa.starter.message.shared.ConfigCache;
import io.softa.starter.message.sms.entity.SmsProviderConfig;

/**
 * Read-through cache for {@link SmsProviderConfig} lookups.
 * See {@link ConfigCache} for semantics; this subclass only binds the
 * cache keys and entity type for the SMS channel.
 */
@Component
public class SmsConfigCache extends ConfigCache<SmsProviderConfig> {

    public SmsConfigCache() {
        super("sms:provider-config:id:", "sms:provider-config:default:tenant:", SmsProviderConfig.class);
    }
}
