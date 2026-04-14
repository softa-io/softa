package io.softa.starter.message.sms.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;

/**
 * Implementation of {@link SmsTemplateProviderBindingService}.
 * <p>
 * Queries enabled bindings for a template, ordered by {@code sortOrder} ascending.
 * Platform-level queries use the {@code @CrossTenant} self-proxy pattern to bypass
 * ORM tenant isolation.
 */
@Service
public class SmsTemplateProviderBindingServiceImpl extends EntityServiceImpl<SmsTemplateProviderBinding, Long> implements SmsTemplateProviderBindingService {

    @Override
    public List<SmsTemplateProviderBinding> findByTemplateId(Long templateId) {
        Filters filters = new Filters()
                .eq(SmsTemplateProviderBinding::getTemplateId, templateId)
                .eq(SmsTemplateProviderBinding::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(SmsTemplateProviderBinding::getSortOrder));
        return this.searchList(flexQuery);
    }

    @Override
    @CrossTenant
    public List<SmsTemplateProviderBinding> findPlatformBindingsByTemplateId(Long templateId) {
        Filters filters = new Filters()
                .eq("tenantId", 0L)
                .eq(SmsTemplateProviderBinding::getTemplateId, templateId)
                .eq(SmsTemplateProviderBinding::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(SmsTemplateProviderBinding::getSortOrder));
        return this.searchList(flexQuery);
    }
}

