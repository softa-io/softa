package io.softa.framework.orm.service;

import java.util.List;

import io.softa.framework.orm.entity.TenantInfo;

/**
 * Provides the list of active tenant IDs.
 * Implementation may use Redis cache with database fallback.
 * Callers do not need to know the underlying data source.
 */
public interface TenantInfoService {

    /**
     * Get all active tenant IDs.
     *
     * @return list of active tenant IDs
     */
    List<Long> getActiveTenantIds();

    TenantInfo getTenantInfo(Long tenantId);

    List<TenantInfo> getActiveTenantList();
}
