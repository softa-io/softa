package io.softa.starter.message.sms.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsProviderRegion;
import io.softa.starter.message.sms.service.SmsProviderRegionService;

/**
 * Implementation of {@link SmsProviderRegionService}.
 *
 * <p>{@code regionCode} is an id-FK to {@code country_region.id} (CountryRegion is
 * code-as-id, so the id IS the ISO alpha-2 code): it renders a country picker, and {@code dialCode}
 * is a framework-maintained stored cascade ({@code regionCode.dialCode}). That the code exists in the
 * country_region master is checked on every write path by
 * {@link io.softa.starter.message.sms.validation.SmsProviderRegionWriteValidator}.
 */
@Service
public class SmsProviderRegionServiceImpl extends EntityServiceImpl<SmsProviderRegion, Long>
        implements SmsProviderRegionService {

    @Override
    public List<SmsProviderRegion> findEnabledByRegion(String regionCode) {
        // active = true is appended by the framework's active control.
        return searchList(new FlexQuery(
                new Filters().eq(SmsProviderRegion::getRegionCode, regionCode),
                Orders.ofAsc(SmsProviderRegion::getPriority)));
    }
}
