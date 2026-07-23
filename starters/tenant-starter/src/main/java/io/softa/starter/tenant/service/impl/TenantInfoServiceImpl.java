package io.softa.starter.tenant.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.enums.TenantProvisioningStatus;
import io.softa.starter.tenant.enums.TenantStatus;

/**
 * Default implementation of the framework {@link TenantInfoService} SPI, living in
 * tenant-starter alongside the {@link TenantInfo} entity. The framework SPI exposes only
 * non-entity methods (active ids / isTenantActive / deactivate); the entity-returning
 * {@code getTenantInfo} / {@code getActiveTenantList} are internal helpers here.
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
    public boolean isTenantActive(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        TenantInfo tenantInfo = getTenantInfo(tenantId);
        return tenantInfo != null && TenantStatus.ACTIVE.equals(tenantInfo.getStatus());
    }

    @Override
    public void deactivate(Long tenantId) {
        TenantInfo tenant = this.getById(tenantId)
                .orElse(null);
        Assert.notNull(tenant, "Tenant not found for tenantId: {0}", tenantId);
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setSuspendedTime(LocalDateTime.now());
        this.updateOne(tenant);
        // Evict caches so isTenantActive() and active-id filtering see the change immediately;
        // the tenant's users are then forced to re-login on their next request.
        cacheService.clear(RedisConstant.TENANT_INFO + tenantId);
        cacheService.clear(RedisConstant.TENANT_IDS);
    }

    /**
     * Write the tenant's {@link TenantProvisioningStatus} (seed-progress axis; separate from operational
     * status). Idempotent — a no-op when already at the target, so it is safe under MQ redelivery and
     * concurrent seeders. Evicts the cached TenantInfo so readers see it; login is unaffected (it keys off
     * {@code status == ACTIVE}, not this axis).
     */
    public void markProvisioningStatus(Long tenantId, TenantProvisioningStatus status) {
        TenantInfo tenant = this.getById(tenantId).orElse(null);
        Assert.notNull(tenant, "Tenant not found for tenantId: {0}", tenantId);
        if (status.equals(tenant.getProvisioningStatus())) {
            return;
        }
        tenant.setProvisioningStatus(status);
        this.updateOne(tenant);
        cacheService.clear(RedisConstant.TENANT_INFO + tenantId);
    }

    /** Cached single-tenant lookup — internal to tenant-starter (not part of the framework SPI). */
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

    /** All ACTIVE tenants — internal helper feeding {@link #getActiveTenantIds()}. */
    public List<TenantInfo> getActiveTenantList() {
        return jdbcService.selectMetaEntityList(TenantInfo.class, null)
                .stream()
                .filter(tenant -> TenantStatus.ACTIVE.equals(tenant.getStatus()))
                .toList();
    }
}
