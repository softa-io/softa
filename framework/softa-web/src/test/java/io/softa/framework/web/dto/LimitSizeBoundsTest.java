package io.softa.framework.web.dto;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.FlexQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `/searchList` takes a stated limitSize at its word, or refuses it. (`/searchName` reads a page now, and
 * its page size is held to the same bounds by {@code Page}: see {@link SearchNameParamsTest}.)
 *
 * <p>Both DTOs read {@code limitSize == null || limitSize < 1} as "use the default", which meant a
 * request for zero rows came back with a default page of them. The framework had in fact already
 * decided the other way — {@link FlexQuery#setLimitSize} rejects anything {@code <= 0} — and the
 * fallback here is precisely what kept that check from ever being reached from an API request. So
 * this is not a new rule; it is the two layers finally saying the same thing.
 *
 * <p>Rejecting rather than returning empty, for the same reason: {@code setLimitSize} is called from
 * six places inside the ORM (topN resolution, one-to-many and many-to-many expansion, timeline
 * reads), and loosening it so that a computed zero returns nothing instead of throwing would turn
 * the loudest of those failures into the quietest.
 */
class LimitSizeBoundsTest {

    // ---- /searchList ----

    @Test
    void searchListOmittingTheLimitTakesTheDefault() {
        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(new SearchListParams());
        assertThat(flexQuery.getLimitSize()).isEqualTo(BaseConstant.DEFAULT_PAGE_SIZE);
    }

    @Test
    void searchListRejectsZero() {
        assertThatThrownBy(() -> SearchListParams.convertParamsToFlexQuery(searchList(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void searchListRejectsANegativeLimit() {
        assertThatThrownBy(() -> SearchListParams.convertParamsToFlexQuery(searchList(-10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void searchListHonoursAStatedLimit() {
        assertThat(SearchListParams.convertParamsToFlexQuery(searchList(1)).getLimitSize()).isOne();
        assertThat(SearchListParams.convertParamsToFlexQuery(searchList(BaseConstant.MAX_BATCH_SIZE))
                .getLimitSize()).isEqualTo(BaseConstant.MAX_BATCH_SIZE);
    }

    @Test
    void searchListStillCapsAtTheBatchMaximum() {
        // 10000, not FlexQuery's 100000: that higher ceiling belongs to export, which sets the limit
        // on the FlexQuery directly and never passes through this DTO.
        assertThatThrownBy(() ->
                SearchListParams.convertParamsToFlexQuery(searchList(BaseConstant.MAX_BATCH_SIZE + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
    }

    private static SearchListParams searchList(Integer limitSize) {
        SearchListParams params = new SearchListParams();
        params.setLimitSize(limitSize);
        return params;
    }
}
