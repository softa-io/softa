package io.softa.starter.message.sms.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsProviderConfig;

import java.util.Optional;

/**
 * CRUD service for SMS provider configurations.
 */
public interface SmsProviderConfigService extends EntityService<SmsProviderConfig, Long> {

    /**
     * Find the current tenant's default enabled SMS provider config.
     * ORM automatically applies {@code WHERE tenant_id = currentTenantId}.
     */
    Optional<SmsProviderConfig> findTenantDefault();

    /**
     * Find the platform-level default SMS provider config (tenant_id = 0).
     * Uses {@code @CrossTenant} to bypass ORM tenant filtering.
     */
    Optional<SmsProviderConfig> findPlatformDefault();
}
