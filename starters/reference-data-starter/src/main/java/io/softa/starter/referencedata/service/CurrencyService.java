package io.softa.starter.referencedata.service;

import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.referencedata.entity.Currency;

/**
 * CRUD + lookup service for {@link Currency}. {@link #findByCode(String)}
 * is the primary access path and is cached.
 */
public interface CurrencyService extends EntityService<Currency, Long> {

    /**
     * Primary lookup by ISO 4217 alpha-3 code. Cached.
     *
     * @param code ISO 4217 alpha-3 (USD/CNY/EUR/...); case-sensitive
     * @return the matching currency, or empty when not found
     */
    Optional<Currency> findByCode(String code);
}
