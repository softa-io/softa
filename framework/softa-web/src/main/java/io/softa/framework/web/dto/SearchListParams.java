package io.softa.framework.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.*;
import io.softa.framework.orm.enums.ConvertType;

/**
 * SearchListParams for /searchList API.
 * <p>
 * It is intended solely for API endpoint consumption and is not suitable for internal service logic.
 * <p>
 * Support querying the latest data of each group by using `MAX` + `groupBy`, such as
 *      AggFunctions: ["MAX", "createdTime", "newestTime"],
 *      GroupBy: ["deptId"],
 */
@Data
@Schema(name = "SearchListParams")
public class SearchListParams {

    @Schema(description = "Fields list to get, empty means all fields of the model.", example = "[\"id\", \"name\"]")
    private List<String> fields;

    private Filters filters;

    private Orders orders;

    private AggFunctions aggFunctions;

    @Schema(description = "Limit size for searchList, default 50.", example = "50")
    private Integer limitSize;

    @Schema(description = "Fields to group by, empty means no grouping.", example = "[]")
    private List<String> groupBy;

    @Schema(description = "Pivot split field list.", example = "[]")
    private List<String> splitBy;

    @Schema(description = "Effective date, default is `Today`. Ignored when `acrossTimeline` is true.")
    private LocalDate effectiveDate;

    @Schema(description = "Timeline models only: when true, return ALL version slices (skip the "
            + "effective-date clamp) — e.g. a record's full version list. Default false returns the "
            + "single slice effective on `effectiveDate`.")
    private Boolean acrossTimeline;

    @Schema(description = "Sub queries for relational fields: {fieldName: SubQuery}", example = "{}")
    private Map<String, SubQuery> subQueries;

    /**
     * Convert SearchListParams to FlexQuery.
     *
     * @param searchListParams SearchListParams
     * @return FlexQuery
     */
    static public FlexQuery convertParamsToFlexQuery(SearchListParams searchListParams) {
        if (searchListParams == null) {
            searchListParams = new SearchListParams();
        }
        FlexQuery flexQuery = new FlexQuery(searchListParams.getFilters(), searchListParams.getOrders());
        flexQuery.setFields(searchListParams.getFields());
        flexQuery.setConvertType(ConvertType.REFERENCE);
        flexQuery.setGroupBy(searchListParams.getGroupBy());
        // Set AggFunction parameters
        flexQuery.setAggFunctions(searchListParams.getAggFunctions());
        // limitSize: absent means "use the default"; present means the caller stated a size, and a
        // stated size out of range is rejected at BOTH ends. It used to be rejected only at the top,
        // while 0 and negatives fell back to the default — so a caller computing a size wrongly got a
        // short page and no signal, and the silence read as "no validation at all" to anyone testing
        // it against a table holding fewer rows than the default.
        Integer limitSize = searchListParams.getLimitSize();
        if (limitSize == null) {
            limitSize = BaseConstant.DEFAULT_PAGE_SIZE;
        } else {
            Assert.isTrue(limitSize >= 1,
                    "API `searchList` limitSize must be a positive integer, but got {0}.", limitSize);
            Assert.isTrue(limitSize <= BaseConstant.MAX_BATCH_SIZE,
                    "API `searchList` cannot exceed the maximum limit of {0}.", BaseConstant.MAX_BATCH_SIZE);
        }
        flexQuery.setLimitSize(limitSize);
        // Set SubQuery parameters
        if (!CollectionUtils.isEmpty(searchListParams.getSubQueries())) {
            SubQueries subQueries = new SubQueries();
            subQueries.setQueryMap(searchListParams.getSubQueries());
            flexQuery.setSubQueries(subQueries);
        }
        ContextHolder.getContext().setEffectiveDate(searchListParams.getEffectiveDate());
        // Timeline: opt into the full version list (no-op for non-timeline models).
        if (Boolean.TRUE.equals(searchListParams.getAcrossTimeline())) {
            flexQuery.acrossTimelineData();
        }
        return flexQuery;
    }
}
