package io.softa.starter.metadata.sequence.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.sequence.exception.SequenceNotFoundException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.sequence.entity.SysSequence;
import io.softa.starter.metadata.sequence.service.SysSequenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Plain {@link EntityServiceImpl} for {@link SysSequence}. Cache eviction
 * is handled inline in service mutation methods, following the same direct
 * clear pattern used by other modules.
 */
@Service
@RequiredArgsConstructor
public class SysSequenceServiceImpl extends EntityServiceImpl<SysSequence, Long>
        implements SysSequenceService {

    private final CacheService cacheService;

    @Override
    public Long createOne(SysSequence entity) {
        Long id = super.createOne(entity);
        evictByEntity(entity);
        return id;
    }

    @Override
    public List<Long> createList(List<SysSequence> entities) {
        List<Long> ids = super.createList(entities);
        Set<String> evicted = new HashSet<>();
        if (entities != null) {
            for (SysSequence entity : entities) {
                evictByEntity(entity, evicted);
            }
        }
        return ids;
    }

    @Override
    public boolean updateOne(SysSequence entity) {
        Long id = entity == null ? null : entity.getId();
        SysSequence before = id == null ? null : super.getById(id).orElse(null);
        boolean updated = super.updateOne(entity);
        if (updated) {
            evictByEntity(before);
        }
        return updated;
    }

    @Override
    public boolean updateOne(SysSequence entity, boolean ignoreNull) {
        Long id = entity == null ? null : entity.getId();
        SysSequence before = id == null ? null : super.getById(id).orElse(null);
        boolean updated = super.updateOne(entity, ignoreNull);
        if (updated) {
            evictByEntity(before);
        }
        return updated;
    }

    @Override
    public boolean updateList(List<SysSequence> entities) {
        Map<Long, SysSequence> beforeById = new HashMap<>();
        if (entities != null) {
            List<Long> ids = new ArrayList<>(entities.size());
            for (SysSequence entity : entities) {
                Long id = entity == null ? null : entity.getId();
                if (id != null) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                for (SysSequence row : super.getByIds(ids)) {
                    if (row != null && row.getId() != null) {
                        beforeById.put(row.getId(), row);
                    }
                }
            }
        }
        boolean updated = super.updateList(entities);
        if (updated) {
            Set<String> evicted = new HashSet<>();
            if (entities != null) {
                for (SysSequence after : entities) {
                    Long id = after == null ? null : after.getId();
                    SysSequence before = id == null ? null : beforeById.get(id);
                    evictByEntity(before, evicted);
                }
            }
        }
        return updated;
    }

    @Override
    public boolean deleteById(Long id) {
        SysSequence before = id == null ? null : super.getById(id).orElse(null);
        boolean deleted = super.deleteById(id);
        if (deleted) {
            evictByEntity(before);
        }
        return deleted;
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        List<SysSequence> rows = ids == null || ids.isEmpty()
                ? List.of()
                : super.searchList(new Filters().in(SysSequence::getId, ids));
        boolean deleted = super.deleteByIds(ids);
        if (deleted) {
            Set<String> evicted = new HashSet<>();
            for (SysSequence row : rows) {
                evictByEntity(row, evicted);
            }
        }
        return deleted;
    }

    @Override
    public SysSequence loadConfigByCode(String code) {
        Long tenantId = ContextHolder.getContext().getTenantId();
        String key = cacheKey(tenantId, code);
        SysSequence cached = cacheService.get(key, SysSequence.class);
        if (cached != null) {
            return cached;
        }

        FlexQuery q = new FlexQuery(new Filters().eq(SysSequence::getCode, code));
        SysSequence row = super.searchOne(q).orElseThrow(() -> new SequenceNotFoundException(code));
        cacheService.save(key, row, RedisConstant.FIVE_MINUTES);
        return row;
    }

    @Override
    public void evictConfigCache(Long tenantId, String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        if (SystemConfig.env.isEnableMultiTenancy() && tenantId == null) {
            return;
        }
        cacheService.clear(cacheKey(tenantId, code.trim()));
    }

    private void evictByEntity(SysSequence row) {
        evictByEntity(row, null);
    }

    private void evictByEntity(SysSequence row, Set<String> evicted) {
        String key = cacheKeyOf(row);
        if (key == null) {
            return;
        }
        if (evicted != null && !evicted.add(key)) {
            return;
        }
        try {
            cacheService.clear(key);
        } catch (Exception ignored) {
            // Keep mutation flow unaffected by cache cleanup failures.
        }
    }

    private static String cacheKeyOf(SysSequence row) {
        if (row == null || row.getCode() == null) {
            return null;
        }
        if (SystemConfig.env.isEnableMultiTenancy() && row.getTenantId() == null) {
            return null;
        }
        String code = row.getCode().trim();
        if (code.isEmpty()) {
            return null;
        }
        return cacheKey(row.getTenantId(), code);
    }

    private static String cacheKey(Long tenantId, String code) {
        return RedisConstant.SEQUENCE_CONFIG
                + (SystemConfig.env.isEnableMultiTenancy() ? tenantId + ":" : "")
                + code;
    }
}
