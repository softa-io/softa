package io.softa.starter.message.sms.service.impl;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
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
                .eq(SmsTemplateProviderBinding::getTemplateId, templateId);
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
                        "Unknown country/region code {0}.", region));
        binding.setRegionCode(region);
    }
}
