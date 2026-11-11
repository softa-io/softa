package io.softa.starter.message.sms.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsProviderRegion;
import io.softa.starter.message.sms.service.SmsProviderRegionService;
import io.softa.starter.referencedata.service.CountryRegionService;

/**
 * Implementation of {@link SmsProviderRegionService}.
 *
 * <p>{@code regionCode} is an id-FK to {@code country_region.id} (CountryRegion is
 * code-as-id, so the id IS the ISO alpha-2 code): it renders a country picker, and {@code dialCode}
 * is a framework-maintained stored cascade ({@code regionCode.dialCode}) — no manual denormalization
 * here anymore. The write path still asserts the code exists in the country_region master: the
 * relation provides the picker/join but does NOT enforce FK existence on direct API / seed writes.
 */
@Service
public class SmsProviderRegionServiceImpl extends EntityServiceImpl<SmsProviderRegion, Long>
        implements SmsProviderRegionService {

    @Autowired
    private CountryRegionService countryRegionService;

    @Override
    public Long createOne(SmsProviderRegion entity) {
        validateRegionCode(entity);
        return super.createOne(entity);
    }

    @Override
    public boolean updateOne(SmsProviderRegion entity) {
        validateRegionCode(entity);
        return super.updateOne(entity);
    }

    @Override
    public List<SmsProviderRegion> findEnabledByRegion(String regionCode) {
        // active = true is appended by the framework's active control.
        return searchList(new FlexQuery(
                new Filters().eq(SmsProviderRegion::getRegionCode, regionCode),
                Orders.ofAsc(SmsProviderRegion::getPriority)));
    }

    private void validateRegionCode(SmsProviderRegion region) {
        if (!StringUtils.hasText(region.getRegionCode())) {
            throw new BusinessException("Region code is required.");
        }
        countryRegionService.findByCode(region.getRegionCode())
                .orElseThrow(() -> new BusinessException(
                        "Unknown country/region code {0}. "
                      + "Load the country/region reference data first.",
                        region.getRegionCode()));
    }
}
