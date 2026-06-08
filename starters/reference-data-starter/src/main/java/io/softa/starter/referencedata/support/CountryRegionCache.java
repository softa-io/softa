package io.softa.starter.referencedata.support;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.CacheService;
import io.softa.starter.referencedata.entity.CountryRegion;

/**
 * Read-through cache for {@link CountryRegion} lookups. Mirrors
 * {@code SmsConfigCache} / {@code MailConfigCache} patterns. TTL is set high
 * (1 hour) because reference data rarely changes — operators editing a row
 * should call {@link #evictByCode(String)} explicitly via admin tooling.
 */
@Slf4j
@Component
public class CountryRegionCache {

    private static final int TTL_SECONDS = 3600;
    private static final String KEY_CODE = "ref:country-region:code:";

    @Autowired
    private CacheService cacheService;

    public CountryRegion getByCode(String code, Supplier<CountryRegion> loader) {
        if (code == null) return loader.get();
        String key = KEY_CODE + code;
        CountryRegion cached = cacheService.get(key, CountryRegion.class);
        if (cached != null) return cached;
        CountryRegion loaded = loader.get();
        if (loaded != null) cacheService.save(key, loaded, TTL_SECONDS);
        return loaded;
    }

    public void evictByCode(String code) {
        if (code == null) return;
        cacheService.clear(KEY_CODE + code);
    }
}
