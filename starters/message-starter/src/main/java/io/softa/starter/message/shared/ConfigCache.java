package io.softa.starter.message.shared;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.service.CacheService;

/**
 * Read-through Redis cache for a server/provider config entity, shared by the
 * mail and sms channels.
 * <p>
 * Backed by {@link CacheService} (Redis), so evictions are visible across every
 * application instance. TTL is short — 5 minutes — so even an operator who
 * forgets to evict will see new credentials on the next boundary.
 * <p>
 * Two keys per entity:
 * <ul>
 *   <li>{@code {idPrefix}{id}} — direct lookup by primary key</li>
 *   <li>{@code {defaultPrefix}{tenantId}} — default config for a tenant
 *       ({@code tenantId = 0} for platform-level)</li>
 * </ul>
 * Concrete subclasses supply the two key prefixes and the entity {@link Class}
 * (needed for {@link CacheService#get(String, Class)}); they remain distinct
 * {@code @Component} beans so callers can autowire by channel-specific type.
 */
public abstract class ConfigCache<T extends AbstractModel> {

    private static final int TTL_SECONDS = 300;

    private final String keyId;
    private final String keyDefault;
    private final Class<T> type;

    @Autowired
    private CacheService cacheService;

    protected ConfigCache(String keyIdPrefix, String keyDefaultPrefix, Class<T> type) {
        this.keyId = keyIdPrefix;
        this.keyDefault = keyDefaultPrefix;
        this.type = type;
    }

    public T getById(Long id, Supplier<T> loader) {
        if (id == null) return loader.get();
        String key = keyId + id;
        T cached = cacheService.get(key, type);
        if (cached != null) return cached;
        T loaded = loader.get();
        if (loaded != null) cacheService.save(key, loaded, TTL_SECONDS);
        return loaded;
    }

    public T getDefault(Supplier<T> loader) {
        String key = keyDefault + currentTenantId();
        T cached = cacheService.get(key, type);
        if (cached != null) return cached;
        T loaded = loader.get();
        if (loaded != null) {
            cacheService.save(key, loaded, TTL_SECONDS);
            // mirror under the id cache so the next delivery-processor lookup hits warm
            cacheService.save(keyId + loaded.getId(), loaded, TTL_SECONDS);
        }
        return loaded;
    }

    /** Called after update / delete to drop the cached config everywhere. */
    public void evictById(Long id) {
        if (id == null) return;
        cacheService.clear(keyId + id);
        // we don't know the tenant, so clear both plausible default keys
        cacheService.clear(keyDefault + currentTenantId());
        cacheService.clear(keyDefault + 0L);
    }

    private static long currentTenantId() {
        Context ctx = ContextHolder.getContext();
        return ctx != null && ctx.getTenantId() != null ? ctx.getTenantId() : 0L;
    }
}
