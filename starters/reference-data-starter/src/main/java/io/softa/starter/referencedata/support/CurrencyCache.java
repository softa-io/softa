package io.softa.starter.referencedata.support;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.CacheService;
import io.softa.starter.referencedata.entity.Currency;

/**
 * Read-through cache for {@link Currency} lookups. Mirrors
 * {@code CountryRegionCache}. Currency master data is essentially fixed
 * (ISO 4217 changes once a decade), so TTL is high.
 */
@Slf4j
@Component
public class CurrencyCache {

    private static final int TTL_SECONDS = 3600;
    private static final String KEY_CODE = "ref:currency:code:";

    @Autowired
    private CacheService cacheService;

    public Currency getByCode(String code, Supplier<Currency> loader) {
        if (code == null) return loader.get();
        String key = KEY_CODE + code;
        Currency cached = cacheService.get(key, Currency.class);
        if (cached != null) return cached;
        Currency loaded = loader.get();
        if (loaded != null) cacheService.save(key, loaded, TTL_SECONDS);
        return loaded;
    }

    public void evictByCode(String code) {
        if (code == null) return;
        cacheService.clear(KEY_CODE + code);
    }
}
