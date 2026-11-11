package io.softa.starter.message.sms.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsProviderConfig;

/**
 * CRUD service for SMS provider configurations.
 */
public interface SmsProviderConfigService extends EntityService<SmsProviderConfig, Long> {

    /**
     * Enabled configs marked {@code isDefault=true}, ordered by
     * {@code priority} asc. Used by {@code SmsProviderDispatcher} as the
     * catchall tier when no {@code sms_provider_region} row matches the
     * recipient's country. Empty when no default is marked.
     */
    List<SmsProviderConfig> findEnabledDefaults();
}
