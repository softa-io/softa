package io.softa.starter.message.sms.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;

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
    public List<SmsProviderConfig> findEnabledDefaults() {
        // active = true is appended by the framework's active control.
        return searchList(new FlexQuery(
                new Filters().eq(SmsProviderConfig::getIsDefault, true),
                Orders.ofAsc(SmsProviderConfig::getPriority)));
    }
}
