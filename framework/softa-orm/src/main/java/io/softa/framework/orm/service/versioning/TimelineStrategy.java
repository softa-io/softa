package io.softa.framework.orm.service.versioning;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.entity.TimelineSlice;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.TimelineService;

/**
 * The {@link VersioningStrategy} for timeline models. The slice interval-maintenance
 * algorithm itself stays in {@link TimelineService}; this strategy adapts it to the seam:
 * writes route into slice creation/update, reads are clamped to the context effective date,
 * and update permission targets are remapped from the physical {@code sliceId} to the
 * trusted logical {@code id}.
 *
 * @param <K> id class
 */
@Service
public class TimelineStrategy<K extends Serializable> implements VersioningStrategy {

    private final JdbcService<K> jdbcService;
    private final TimelineService timelineService;

    public TimelineStrategy(JdbcService<K> jdbcService, TimelineService timelineService) {
        this.jdbcService = jdbcService;
        this.timelineService = timelineService;
    }

    @Override
    public List<Map<String, Object>> create(String modelName, List<Map<String, Object>> rows) {
        return timelineService.createSlices(modelName, rows);
    }

    @Override
    public Integer update(String modelName, List<Map<String, Object>> rows, Set<String> toUpdateFields) {
        return timelineService.updateSlices(modelName, rows);
    }

    @Override
    public Collection<Serializable> resolveTargetIds(String modelName, List<Map<String, Object>> rows) {
        List<Serializable> sliceIds = rows.stream()
                .map(row -> (Serializable) row.get(ModelConstant.SLICE_ID))
                .collect(Collectors.toList());
        FlexQuery flexQuery = new FlexQuery(Arrays.asList(ModelConstant.ID, ModelConstant.SLICE_ID),
                new Filters().in(ModelConstant.SLICE_ID, sliceIds));
        List<Map<String, Object>> sliceList = jdbcService.selectByFilter(modelName, flexQuery);
        Map<Serializable, Serializable> sliceMap = sliceList.stream()
                .collect(Collectors.toMap(row -> (Serializable) row.get(ModelConstant.SLICE_ID),
                        row -> (Serializable) row.get(ModelConstant.ID)));
        // Fill timeline model business primary key `id`. The input id parameter is not reliable.
        rows.forEach(row -> {
            Serializable sliceId = (Serializable) row.get(ModelConstant.SLICE_ID);
            Assert.isTrue(sliceMap.containsKey(sliceId),
                    "The timeline model {0} does not have data for sliceId {1}!", modelName, sliceId);
            row.put(ModelConstant.ID, sliceMap.get(sliceId));
        });
        return sliceMap.values();
    }

    @Override
    public List<Map<String, Object>> fetchByIds(String modelName, List<? extends Serializable> ids,
                                                List<String> fields, ConvertType convertType) {
        // Read the slice effective at the context date for each logical id.
        Filters filters = timelineService.appendTimelineFilters(modelName, new Filters().in(ModelConstant.ID, ids));
        FlexQuery flexQuery = new FlexQuery(fields, filters);
        flexQuery.setConvertType(convertType);
        return jdbcService.selectByFilter(modelName, flexQuery);
    }

    @Override
    public Filters scopeRead(String modelName, Filters filters) {
        return timelineService.appendTimelineFilters(modelName, filters);
    }

    @Override
    public Filters scopeRead(String modelName, FlexQuery flexQuery) {
        return timelineService.appendTimelineFilters(modelName, flexQuery);
    }

    @Override
    public TimelineSlice versionSlice(String modelName, Serializable versionId) {
        return timelineService.getTimelineSlice(modelName, versionId);
    }

    @Override
    public boolean deleteVersion(String modelName, TimelineSlice slice) {
        return timelineService.deleteSlice(modelName, slice);
    }
}
