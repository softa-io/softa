package io.softa.framework.web.filter.context;

import io.softa.framework.base.context.Context;

/**
 * SPI for enriching a {@link Context} after core identity (UserInfo) has been set.
 *
 * <p>Business starters (e.g., HR module, permission module) provide Spring beans
 * implementing this interface. {@link ContextBuilder} collects all enrichers via
 * dependency injection and invokes them during {@code buildUserContext()}.
 *
 * <p>Enrichers are called <em>after</em> {@code UserInfo}, {@code tenantId}, and
 * {@code language} are already populated on the context, so implementations may
 * rely on {@code context.getUserId()} and other base fields.
 *
 * <p>Typical implementations read cached data from Redis (via {@code CacheService})
 * and fall back to a database query on cache-miss, following the same pattern used
 * for {@code UserInfo}.
 */
@FunctionalInterface
public interface ContextEnricher {

    /**
     * Enrich the given context with additional data.
     *
     * @param context the context to enrich (UserInfo is already set)
     */
    void enrich(Context context);
}

