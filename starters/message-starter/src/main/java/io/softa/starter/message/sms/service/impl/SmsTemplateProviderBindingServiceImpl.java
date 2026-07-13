package io.softa.starter.message.sms.service.impl;

import java.util.Locale;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;
import io.softa.starter.referencedata.service.CountryRegionService;

/**
 * Implementation of {@link SmsTemplateProviderBindingService}.
 * <p>
 * Queries enabled bindings for a template, ordered by {@code priority} ascending.
 * Platform-level queries use the {@code @CrossTenant} self-proxy pattern to bypass
 * ORM tenant isolation.
 */
@Service
public class SmsTemplateProviderBindingServiceImpl extends EntityServiceImpl<SmsTemplateProviderBinding, Long> implements SmsTemplateProviderBindingService {

    @Autowired
    private CountryRegionService countryRegionService;

    @Override
    public Long createOne(SmsTemplateProviderBinding entity) {
        normalizeAndValidateRegion(entity);
        return super.createOne(entity);
    }

    @Override
    public boolean updateOne(SmsTemplateProviderBinding entity) {
        normalizeAndValidateRegion(entity);
        return super.updateOne(entity);
    }

    @Override
    public List<SmsTemplateProviderBinding> findByTemplateId(Long templateId) {
        Filters filters = new Filters()
                .eq(SmsTemplateProviderBinding::getTemplateId, templateId)
                .eq(SmsTemplateProviderBinding::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(SmsTemplateProviderBinding::getPriority));
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
                Orders.ofAsc(SmsTemplateProviderBinding::getPriority));
        return this.searchList(flexQuery);
    }

    private void normalizeAndValidateRegion(SmsTemplateProviderBinding binding) {
        if (!StringUtils.hasText(binding.getRegionCode())) {
            binding.setRegionCode("");
            return;
        }
        String region = binding.getRegionCode().trim().toUpperCase(Locale.ROOT);
        countryRegionService.findByCode(region)
                .orElseThrow(() -> new BusinessException(
                        "Unknown country code {0}; not in country_region.", region));
        binding.setRegionCode(region);
    }
}
