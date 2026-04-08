package io.softa.starter.user.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.PermissionInfo;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.filter.context.ContextEnricher;

/**
 * Enriches the {@link Context} with {@link PermissionInfo} (role codes and permission codes).
 *
 * <p>Follows the same cache-first pattern as {@code UserInfo}: reads from Redis
 * key {@code user-permissions:{userId}}, populated by the permission management
 * module when roles/permissions change.
 *
 * <p>If the cache entry is missing, this enricher logs a warning and leaves
 * {@code permissionInfo} as {@code null}. A future implementation should query
 * the RBAC tables on cache-miss and re-cache the result.
 */
@Slf4j
@Component
public class PermissionInfoContextEnricher implements ContextEnricher {

    private final CacheService cacheService;

    public PermissionInfoContextEnricher(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public void enrich(Context context) {
        if (context.getUserId() == null) {
            return;
        }
        String key = RedisConstant.PERMISSION_INFO + context.getUserId();
        PermissionInfo permission = cacheService.get(key, PermissionInfo.class);
        if (permission != null) {
            context.setPermissionInfo(permission);
        } else {
            log.debug("PermissionInfo not found in cache for userId={}", context.getUserId());
            // TODO: Query RBAC tables on cache-miss and re-cache.
            // Example:
            //   PermissionInfo queried = permissionService.loadPermissions(context.getUserId());
            //   cacheService.save(key, queried, RedisConstant.ONE_DAY);
            //   context.setPermissionInfo(queried);
        }
    }
}

