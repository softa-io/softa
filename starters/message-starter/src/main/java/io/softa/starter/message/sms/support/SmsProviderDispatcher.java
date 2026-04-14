package io.softa.starter.message.sms.support;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.service.SmsProviderConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective SMS provider config for sending.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Tenant's own default config (ORM auto-filters by current tenant_id)</li>
 *   <li>Platform-level default config (tenant_id = 0, via {@code @CrossTenant})</li>
 *   <li>{@link BusinessException} if neither is found</li>
 * </ol>
 */
@Component
public class SmsProviderDispatcher {

    @Autowired
    private SmsProviderConfigService configService;

    /**
     * Resolve the effective SMS provider config for the current tenant.
     */
    public SmsProviderConfig resolveProvider() {
        return configService.findTenantDefault()
                .or(configService::findPlatformDefault)
                .orElseThrow(() -> new BusinessException(
                        "No SMS provider config found. "
                        + "Please configure one in SmsProviderConfig."));
    }

    /**
     * Resolve a specific provider config by ID, bypassing dispatch logic.
     */
    public SmsProviderConfig resolveProviderById(Long id) {
        return configService.getById(id)
                .orElseThrow(() -> new BusinessException(
                        "SMS provider config with ID {0} not found.", id));
    }
}
