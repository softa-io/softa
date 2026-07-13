package io.softa.starter.message.sms.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsProviderRegion;

/**
 * CRUD + query service for {@link SmsProviderRegion} routing rows.
 */
public interface SmsProviderRegionService extends EntityService<SmsProviderRegion, Long> {

    /**
     * Enabled routing rows for {@code regionCode}, ordered by {@code priority}
     * ascending. Returns the rows themselves (not joined provider configs) so
     * the caller (dispatcher) can apply its own join + isDefault catchall logic.
     *
     * @param regionCode ISO 3166-1 alpha-2 country code parsed from the recipient's E.164 number
     * @return ordered enabled rows; empty when no row matches the region
     */
    List<SmsProviderRegion> findEnabledByRegion(String regionCode);
}
