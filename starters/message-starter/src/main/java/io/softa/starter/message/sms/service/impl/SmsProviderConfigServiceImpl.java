package io.softa.starter.message.sms.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.service.SmsProviderConfigService;

/**
 * Implementation of {@link SmsProviderConfigService}.
 */
@Service
public class SmsProviderConfigServiceImpl extends EntityServiceImpl<SmsProviderConfig, Long>
        implements SmsProviderConfigService {

    @Override
    public Optional<SmsProviderConfig> findTenantDefault() {
        Filters filters = new Filters()
                .eq(SmsProviderConfig::getIsDefault, true)
                .eq(SmsProviderConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(SmsProviderConfig::getSortOrder));
        List<SmsProviderConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    @CrossTenant
    public Optional<SmsProviderConfig> findPlatformDefault() {
        Filters filters = new Filters()
                .eq("tenantId", 0L)
                .eq(SmsProviderConfig::getIsDefault, true)
                .eq(SmsProviderConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(SmsProviderConfig::getSortOrder));
        List<SmsProviderConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
}
