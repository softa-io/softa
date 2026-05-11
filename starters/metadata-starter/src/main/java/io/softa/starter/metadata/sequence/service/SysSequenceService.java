package io.softa.starter.metadata.sequence.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.metadata.sequence.entity.SysSequence;

/**
 * Generic CRUD for {@link SysSequence} rows. Driven by ORM metadata —
 * search / get / update / delete come from {@link EntityService}.
 *
 * <p>Mutating operations are intercepted in
 * {@code SysSequenceServiceImpl} to evict the per-(tenant, code) cache on
 * the current instance.
 *
 * <p>v1 admin endpoints close {@code createOne} / {@code deleteById}; admin
 * may only update content fields (template / startValue / mode / cadence /
 * description) — see config validation in the impl.
 */
public interface SysSequenceService extends EntityService<SysSequence, Long> {

    /**
     * Load sequence config by code for the current tenant, with cache.
     */
    SysSequence loadConfigByCode(String code);

    /**
     * Evict config cache for an explicit (tenantId, code) key.
     */
    void evictConfigCache(Long tenantId, String code);
}
