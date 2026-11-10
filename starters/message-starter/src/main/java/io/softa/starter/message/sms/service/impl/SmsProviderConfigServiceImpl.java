package io.softa.starter.message.sms.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.FilterControl;
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
        // Catchall tier, tenant-first: the tenant's own enabled defaults win
        // outright; only a tenant with none falls back to the (invisible)
        // platform-tier defaults. The two tiers never interleave.
        List<SmsProviderConfig> own = searchList(new FlexQuery(
                new Filters()
                        .eq(SmsProviderConfig::getIsDefault, true)
                        .eq(SmsProviderConfig::getTenantId, TenantScopes.currentTenantOrPlatform()),
                Orders.ofAsc(SmsProviderConfig::getPriority)));
        if (!own.isEmpty()) {
            return own;
        }
        return searchList(new FlexQuery(
                new Filters()
                        .eq(SmsProviderConfig::getIsDefault, true)
                        .eq(SmsProviderConfig::getTenantId, TenantScopes.PLATFORM),
                Orders.ofAsc(SmsProviderConfig::getPriority)));
    }

    @Override
    @CrossTenant
    public Optional<SmsProviderConfig> findVisibleById(Long id) {
        // Disabled providers stay resolvable by id: a prepared record replays
        // through the very provider it was routed to when it was prepared.
        FlexQuery flexQuery = new FlexQuery(new Filters()
                .eq(SmsProviderConfig::getId, id)
                .in(SmsProviderConfig::getTenantId, TenantScopes.currentPlusPlatform()));
        flexQuery.setFilterControl(FilterControl.bypassActiveControl());
        return searchOne(flexQuery);
    }
}
