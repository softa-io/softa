package io.softa.framework.orm.service.impl;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.orm.entity.TenantInfo;
import io.softa.framework.orm.enums.TenantStatus;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;

/**
 * Default implementation of {@link TenantInfoService}.
 * Queries active tenant IDs from Redis cache, falling back to database query.
 */
@Slf4j
@Component
public class TenantInfoServiceImpl extends EntityServiceImpl<TenantInfo, Long> implements TenantInfoService {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private JdbcService<?> jdbcService;

    @Override
    public List<Long> getActiveTenantIds() {
        // Try Redis cache first
        List<Long> tenantIds = cacheService.get(RedisConstant.TENANT_IDS, new TypeReference<>() {});
        if (tenantIds != null && !tenantIds.isEmpty()) {
            return tenantIds;
        }
        // Fallback to database query
        List<TenantInfo> tenants = getActiveTenantList();
        tenantIds = tenants.stream().map(TenantInfo::getId).toList();
        if (!tenantIds.isEmpty()) {
            cacheService.save(RedisConstant.TENANT_IDS, tenantIds, RedisConstant.ONE_MONTH);
        }
        return tenantIds;
    }

    @Override
    public TenantInfo getTenantInfo(Long tenantId) {
        TenantInfo tenantInfo = cacheService.get(RedisConstant.TENANT_INFO + tenantId, TenantInfo.class);
        if (tenantInfo != null) {
            return tenantInfo;
        }
        tenantInfo = this.getById(tenantId).orElse(null);
        if (tenantInfo != null) {
            cacheService.save(RedisConstant.TENANT_INFO + tenantId, tenantInfo);
        }
        return tenantInfo;
    }

    @Override
    public List<TenantInfo> getActiveTenantList() {
        return jdbcService.selectMetaEntityList(TenantInfo.class, null)
                .stream()
                .filter(tenant -> TenantStatus.ACTIVE.equals(tenant.getStatus()))
                .toList();
    }
}
