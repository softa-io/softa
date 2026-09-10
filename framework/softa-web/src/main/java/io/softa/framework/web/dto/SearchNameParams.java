package io.softa.framework.web.dto;

import java.time.LocalDate;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.ConvertType;

/**
 * SearchNameParams
 * The parameters for searching by name.
 * The value is used to match the displayName field by default. Or specify the matchField.
 */
@Data
@Schema(name = "SearchNameParams")
public class SearchNameParams {

    @Schema(description = "Optionally specify the matching field. Default is `searchName` field configured in the model.", example = "searchName")
    private String matchField = ModelConstant.SEARCH_NAME;

    @Schema(description = "The operator to use for the search. Default is CONTAINS.", example = "CONTAINS")
    private Operator operator = Operator.CONTAINS;

    @Schema(description = "The value to match using CONTAINS.", example = "Tom")
    private String value;

    @Schema(description = "Additional fields to fetch, in addition to displayName.", example = "[\"type\"]")
    private List<String> additionalFields;

    @Schema(description = "Context filters for the search. From the relational field or form context.")
    private Filters filters;

    @Schema(description = "Ordering for the search results.")
    private Orders orders;

    @Schema(description = "Limit size for search, default 10.", example = "10")
    private Integer limitSize = BaseConstant.DEFAULT_NAME_LIST_SIZE;

    @Schema(description = "Effective date, default is `Today`.")
    private LocalDate effectiveDate;

    @Schema(description = "The source record parameters from the frontend, used for providing the context of current record in some scenarios, such as searching in the form with relational field context.")
    private SourceRecord sourceRecord;

    /**
     * Convert QueryParams to FlexQuery.
     *
     * @param searchNameParams QueryParams
     * @return FlexQuery
     */
    static public FlexQuery convertParamsToFlexQuery(SearchNameParams searchNameParams) {
        if (searchNameParams == null) {
            searchNameParams = new SearchNameParams();
        }
        Filters filters = searchNameParams.getFilters();
        if (StringUtils.isNotBlank(searchNameParams.getValue())) {
            // Construct the match filters.
            String matchField = StringUtils.isBlank(searchNameParams.getMatchField()) ? ModelConstant.SEARCH_NAME : searchNameParams.getMatchField();
            Operator operator = searchNameParams.getOperator() == null ? Operator.CONTAINS : searchNameParams.getOperator();
            Filters matchFilters = Filters.of(matchField, operator, searchNameParams.getValue());
            filters = Filters.and(filters, matchFilters);
        }
        FlexQuery flexQuery = new FlexQuery(filters, searchNameParams.getOrders());
        flexQuery.setFields(searchNameParams.getAdditionalFields());
        // Set the convert type to REFERENCE.
        flexQuery.setConvertType(ConvertType.REFERENCE);
        // limitSize: absent means "use the default"; present means the caller stated a size, and a
        // stated size out of range is rejected at BOTH ends. It used to be rejected only at the top,
        // while 0 and negatives fell back to the default — so a caller computing a size wrongly got a
        // short page and no signal, and the silence read as "no validation at all" to anyone testing
        // it against a table holding fewer rows than the default.
        Integer limitSize = searchNameParams.getLimitSize();
        if (limitSize == null) {
            limitSize = BaseConstant.DEFAULT_NAME_LIST_SIZE;
        } else {
            Assert.isTrue(limitSize >= 1,
                    "API `searchName` limitSize must be a positive integer, but got {0}.", limitSize);
            Assert.isTrue(limitSize <= BaseConstant.MAX_BATCH_SIZE,
                    "API `searchName` cannot exceed the maximum limit of {0}.", BaseConstant.MAX_BATCH_SIZE);
        }
        flexQuery.setLimitSize(limitSize);
        ContextHolder.getContext().setEffectiveDate(searchNameParams.getEffectiveDate());
        return flexQuery;
    }
}
