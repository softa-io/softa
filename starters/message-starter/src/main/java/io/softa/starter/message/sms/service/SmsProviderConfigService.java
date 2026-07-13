package io.softa.starter.message.sms.service;

import java.util.List;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsProviderConfig;

/**
 * CRUD service for SMS provider configurations.
 */
public interface SmsProviderConfigService extends EntityService<SmsProviderConfig, Long> {

    /**
     * Enabled configs marked {@code isDefault=true}, ordered by
     * {@code priority} asc, within the caller's visibility scope (own tenant
     * plus platform tier). Used by {@code SmsProviderDispatcher} as the
     * catchall tier when no {@code sms_provider_region} row matches the
     * recipient's country. Empty when no default is marked.
     */
    List<SmsProviderConfig> findEnabledDefaults();

    /**
     * Load a config by id within the caller's visibility scope: the caller's
     * own tenant plus the platform tier (tenant_id = 0). Send records
     * legitimately reference platform-level configs, which the implicit
     * single-tenant filter would hide — dispatch and retry paths must resolve
     * ids through this method rather than {@code getById}.
     */
    Optional<SmsProviderConfig> findVisibleById(Long id);
}
