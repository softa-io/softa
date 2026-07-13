package io.softa.starter.message.sms.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.service.SmsProviderConfigService;
import io.softa.starter.message.sms.support.SmsConfigCache;

/**
 * Implementation of {@link SmsProviderConfigService}.
 */
@Service
public class SmsProviderConfigServiceImpl extends EntityServiceImpl<SmsProviderConfig, Long>
        implements SmsProviderConfigService {

    @Autowired
    private SmsConfigCache configCache;

    @Override
    public boolean updateOne(SmsProviderConfig entity) {
        boolean result = super.updateOne(entity);
        if (result) configCache.evictById(entity.getId());
        return result;
    }

    @Override
    public boolean deleteById(Long id) {
        boolean result = super.deleteById(id);
        if (result) configCache.evictById(id);
        return result;
    }

    @Override
    @CrossTenant
    public List<SmsProviderConfig> findEnabledDefaults() {
        // Catchall tier is a platform-overlay read: the tenant's own defaults
        // plus the platform-level (tenant 0) defaults, interleaved by priority.
        Filters filters = new Filters()
                .eq(SmsProviderConfig::getIsDefault, true)
                .eq(SmsProviderConfig::getIsEnabled, true)
                .in(SmsProviderConfig::getTenantId, TenantScopes.currentPlusPlatform());
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(SmsProviderConfig::getPriority));
        return this.searchList(flexQuery);
    }

    @Override
    @CrossTenant
    public Optional<SmsProviderConfig> findVisibleById(Long id) {
        return searchOne(new Filters()
                .eq(SmsProviderConfig::getId, id)
                .in(SmsProviderConfig::getTenantId, TenantScopes.currentPlusPlatform()));
    }
}
