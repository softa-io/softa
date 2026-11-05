package io.softa.framework.web.dto;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A name search is read one page at a time: {@link SearchNameParams#toFlexQuery()} is what to search and
 * {@link SearchNameParams#toPage()} which page of it, so an endpoint that narrows the candidates adds its
 * conditions to the query and passes the page on as it came.
 *
 * <p>The page size keeps the bounds the old {@code limitSize} had — ten when omitted, a stated one honoured
 * or refused, never quietly replaced — now held by {@link Page}, which every paged read shares.
 */
class SearchNameParamsTest {

    // ---- which page ----

    @Test
    void omittingThePageReadsTheFirstTenCandidates() {
        Page<Map<String, Object>> page = new SearchNameParams().toPage();

        assertThat(page.getPageNumber()).isOne();
        assertThat(page.getPageSize()).isEqualTo(BaseConstant.DEFAULT_NAME_LIST_SIZE);
    }

    @Test
    void theNextPageIsTheOneAskedFor() {
        Page<Map<String, Object>> page = params(3, 20).toPage();

        assertThat(page.getPageNumber()).isEqualTo(3);
        assertThat(page.getPageSize()).isEqualTo(20);
    }

    /** A cursor page: one total order from page to page, and nothing counted on each keystroke. */
    @Test
    void thePageKeepsItsOrderAndCountsNothing() {
        Page<Map<String, Object>> page = new SearchNameParams().toPage();

        assertThat(page.isCursorPage()).isTrue();
        assertThat(page.isCount()).isFalse();
    }

    @Test
    void anExplicitNullPageSizeIsStillTheDefault() {
        // The field carries a default value, so null only ever arrives from a body that spelled it
        // out. That is still "I did not choose", not "I chose nothing".
        assertThat(params(1, null).toPage().getPageSize()).isEqualTo(BaseConstant.DEFAULT_NAME_LIST_SIZE);
    }

    @Test
    void aPageSizeOfZeroOrLessIsRefused() {
        assertThatThrownBy(() -> params(1, 0).toPage())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> params(1, -1).toPage())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void aPageSizeAboveTheBatchMaximumIsRefused() {
        assertThat(params(1, BaseConstant.MAX_BATCH_SIZE).toPage().getPageSize()).isEqualTo(BaseConstant.MAX_BATCH_SIZE);
        assertThatThrownBy(() -> params(1, BaseConstant.MAX_BATCH_SIZE + 1).toPage())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
    }

    // ---- what to search ----

    /** How many rows is the page's, so the query carries no limit of its own. */
    @Test
    void theQueryLeavesTheLimitToThePage() {
        assertThat(params(2, 20).toFlexQuery().getLimitSize()).isNull();
    }

    @Test
    void aTypedValueMatchesTheSearchFieldsWithinTheFilters() {
        SearchNameParams params = new SearchNameParams();
        params.setValue("ali");
        params.setFilters(new Filters().eq("active", true));

        String filters = params.toFlexQuery().getFilters().toString();

        assertThat(filters).contains("[\"active\",\"=\",true]").contains("[\"searchName\",\"CONTAINS\",\"ali\"]");
    }

    @Test
    void aStatedMatchFieldAndOperatorAreUsed() {
        SearchNameParams params = new SearchNameParams();
        params.setValue("E01");
        params.setMatchField("code");
        params.setOperator(Operator.START_WITH);

        assertThat(params.toFlexQuery().getFilters().toString()).isEqualTo("[\"code\",\"START WITH\",\"E01\"]");
    }

    @Test
    void theQueryReadsReferencesAndTheAdditionalFields() {
        SearchNameParams params = new SearchNameParams();
        params.setAdditionalFields(List.of("type"));

        FlexQuery query = params.toFlexQuery();

        assertThat(query.getConvertType()).isEqualTo(ConvertType.REFERENCE);
        assertThat(query.getFields()).contains("type");
    }

    private static SearchNameParams params(Integer pageNumber, Integer pageSize) {
        SearchNameParams params = new SearchNameParams();
        params.setPageNumber(pageNumber);
        params.setPageSize(pageSize);
        return params;
    }
}
