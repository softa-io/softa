package io.softa.framework.orm.service.versioning;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.jdbc.JdbcService;

/**
 * The no-op {@link VersioningStrategy} for regular (non-timeline) models: writes go straight
 * to {@link JdbcService}, reads by id preserve id order, and read scoping passes the filters
 * through untouched — byte-for-byte the pre-seam behavior of the non-timeline branches.
 *
 * @param <K> id class
 */
@Service
public class IdentityStrategy<K extends Serializable> implements VersioningStrategy {

    private final JdbcService<K> jdbcService;

    public IdentityStrategy(JdbcService<K> jdbcService) {
        this.jdbcService = jdbcService;
    }

    @Override
    public List<Map<String, Object>> create(String modelName, List<Map<String, Object>> rows) {
        return jdbcService.insertList(modelName, rows);
    }

    @Override
    public Integer update(String modelName, List<Map<String, Object>> rows, Set<String> toUpdateFields) {
        return jdbcService.updateList(modelName, rows, toUpdateFields);
    }

    @Override
    public Collection<Serializable> resolveTargetIds(String modelName, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (Serializable) row.get(ModelConstant.ID)).toList();
    }

    @Override
    public List<Map<String, Object>> fetchByIds(String modelName, List<? extends Serializable> ids,
                                                List<String> fields, ConvertType convertType) {
        return jdbcService.selectByIds(modelName, Cast.of(ids), fields, convertType);
    }

    @Override
    public Filters scopeRead(String modelName, Filters filters) {
        return filters;
    }

    @Override
    public Filters scopeRead(String modelName, FlexQuery flexQuery) {
        return flexQuery.getFilters();
    }
}
