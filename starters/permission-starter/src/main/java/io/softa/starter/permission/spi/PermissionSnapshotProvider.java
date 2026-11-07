package io.softa.starter.permission.spi;


/**
 * SPI (2026-07-14「取快照倒置」接缝): fetch the decision snapshot for a user.
 *
 * <p>The enforce side (route-admission interceptor + data-plane, in
 * {@code permission-starter}) depends only on this contract, never on a
 * concrete enricher — so it carries no {@code user-starter} / login.
 *
 * <p>One impl per process (chosen by deployment, {@code @ConditionalOnMissingBean}):
 * <ul>
 *   <li>monolith / platform: {@code user-starter}'s {@code PermissionInfoEnricher}
 *       (builds from DB, caches to Redis {@code perm:});</li>
 *   <li>pure-enforce microservice: the built-in {@code RedisPermissionSnapshotProvider}
 *       default (reads Redis {@code perm:}, miss → fail-closed; no DB), or a
 *       bespoke {@code RemoteSnapshotProvider} that回源 principal-svc on miss.</li>
 * </ul>
 */
public interface PermissionSnapshotProvider {

    /**
     * Canonical Redis cache-key prefix for per-user permission snapshots
     * ({@code perm:{tenantId}:user:{userId}}). If {@code PermissionInfo} ever
     * changes shape incompatibly, either rely on the tolerant reader (the enricher
     * falls through to a DB rebuild on a deserialization miss) or bump this prefix
     * so old and new serialized values live in disjoint namespaces.
     */
    String CACHE_KEY_PREFIX = "perm:";

    /**
     * Canonical per-user snapshot cache key: {@code perm:{tenantId}:user:{userId}}.
     * Single source of truth shared by the producer ({@code PermissionInfoEnricher}
     * writes it), the invalidator (evicts it), and the standalone reader
     * ({@code RedisPermissionSnapshotProvider} reads it) so the key shape can't drift.
     */
    static String userSnapshotKey(Long tenantId, Long userId) {
        return CACHE_KEY_PREFIX + tenantId + ":user:" + userId;
    }

    /**
     * What a tenant's department paths last changed to, as a mark with no meaning of its own: a snapshot being
     * built compares it before and after, to tell whether the paths it read may have gone stale meanwhile. Kept
     * outside {@link #CACHE_KEY_PREFIX}, so clearing the tenant's snapshots leaves it standing.
     */
    static String departmentPathsMarkKey(Long tenantId) {
        return "dept-paths-mark:" + tenantId;
    }

    /**
     * @param tenantId tenant id (multi-tenant key half)
     * @param userId   user id
     * @return the user's permission snapshot; the impl builds / reads / rebuilds
     *         on miss. May return {@code null} when no snapshot can be produced
     *         (callers must fail-closed).
     */
    PermissionInfo get(Long tenantId, Long userId);
}
