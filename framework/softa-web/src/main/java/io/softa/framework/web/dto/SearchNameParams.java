package io.softa.framework.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;

/**
 * SearchNameParams
 * The parameters for searching by name, one page of candidates at a time.
 * The value is used to match the displayName field by default. Or specify the matchField.
 *
 * <p>Split in two for the read: {@link #toFlexQuery()} is what to search, {@link #toPage()} is which page of
 * it. An endpoint that narrows the candidates (a typed shadow of {@code /{model}/searchName}) adds its
 * conditions to the query and passes the page on as it came.
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

    @Schema(description = "Ordering for the search results. The model's primary key always ends it, so pages "
            + "neither repeat nor skip a candidate.")
    private Orders orders;

    @Schema(description = "Which page of candidates to return, counting from 1. Default 1.", example = "1")
    private Integer pageNumber = BaseConstant.DEFAULT_PAGE_NUMBER;

    @Schema(description = "Candidates per page, default 10 when omitted. Must be within "
            + "[1, 10000] when stated; 0 or a negative value is rejected.", example = "10")
    private Integer pageSize = BaseConstant.DEFAULT_NAME_LIST_SIZE;

    @Schema(description = "Effective date, default is `Today`.")
    private LocalDate effectiveDate;

    @Schema(description = "The source record parameters from the frontend, used for providing the context of current record in some scenarios, such as searching in the form with relational field context.")
    private SourceRecord sourceRecord;

    /**
     * What to search: the match on the search fields, the filters, the orders and the fields to read. How many
     * rows, and which of them, is the page's: see {@link #toPage()}.
     *
     * <p>Sets the effective date on the context, as the read is made in it.
     *
     * @return the query
     */
    public FlexQuery toFlexQuery() {
        Filters searchFilters = filters;
        if (StringUtils.isNotBlank(value)) {
            String field = StringUtils.isBlank(matchField) ? ModelConstant.SEARCH_NAME : matchField;
            Operator matchOperator = operator == null ? Operator.CONTAINS : operator;
            searchFilters = Filters.and(searchFilters, Filters.of(field, matchOperator, value));
        }
        FlexQuery flexQuery = new FlexQuery(searchFilters, orders);
        flexQuery.setFields(additionalFields);
        // Relations and options among the display fields read as references.
        flexQuery.setConvertType(ConvertType.REFERENCE);
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        return flexQuery;
    }

    /**
     * Which page of candidates to read: a cursor page, so consecutive pages neither repeat nor skip a
     * candidate, and nothing is counted. An omitted page size takes the name search's own default of 10; a
     * stated one is honoured or rejected by {@link Page}.
     *
     * @return the page to read
     */
    public Page<Map<String, Object>> toPage() {
        // The field carries a default, so null only arrives from a body that spelled it out: still "I did not choose".
        return Page.ofCursorPage(pageNumber, pageSize == null ? BaseConstant.DEFAULT_NAME_LIST_SIZE : pageSize);
    }
}
