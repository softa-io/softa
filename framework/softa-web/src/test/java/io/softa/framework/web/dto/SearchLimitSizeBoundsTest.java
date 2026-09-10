package io.softa.framework.web.dto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.FlexQuery;

/**
 * How `searchName` / `searchList` treat the caller's `limitSize`.
 *
 * <p>Absent means "use the default". Present means the caller stated a size, and a stated size is
 * rejected at BOTH ends. The lower end used to fall back to the default instead, which is what made
 * zingkey/zingkey-hcm#615 look like missing validation: 0 returned the default page of rows, and
 * against a table holding fewer rows than the default that is indistinguishable from returning
 * everything.
 */
class SearchLimitSizeBoundsTest {

    @Test
    void searchNameDefaultsWhenLimitSizeIsAbsent() {
        SearchNameParams params = new SearchNameParams();
        params.setLimitSize(null);

        FlexQuery flexQuery = SearchNameParams.convertParamsToFlexQuery(params);

        Assertions.assertEquals(BaseConstant.DEFAULT_NAME_LIST_SIZE, flexQuery.getLimitSize());
    }

    @Test
    void searchNameRejectsZeroAndNegativeLimitSize() {
        for (int stated : new int[] {0, -10}) {
            SearchNameParams params = new SearchNameParams();
            params.setLimitSize(stated);

            IllegalArgumentException thrown = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> SearchNameParams.convertParamsToFlexQuery(params));
            // The offending value belongs in the message: a caller computing the size wrongly needs to
            // see what it actually sent, not just that something was invalid.
            Assertions.assertTrue(thrown.getMessage().contains(String.valueOf(stated)),
                    "message should name the rejected value, got: " + thrown.getMessage());
        }
    }

    @Test
    void searchNameRejectsLimitSizeOverTheMaximum() {
        SearchNameParams params = new SearchNameParams();
        params.setLimitSize(BaseConstant.MAX_BATCH_SIZE + 1);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SearchNameParams.convertParamsToFlexQuery(params));
    }

    @Test
    void searchNameKeepsAStatedSizeInRange() {
        SearchNameParams params = new SearchNameParams();
        params.setLimitSize(1);

        Assertions.assertEquals(1, SearchNameParams.convertParamsToFlexQuery(params).getLimitSize());
    }

    @Test
    void searchListDefaultsWhenLimitSizeIsAbsent() {
        SearchListParams params = new SearchListParams();
        params.setLimitSize(null);

        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(params);

        Assertions.assertEquals(BaseConstant.DEFAULT_PAGE_SIZE, flexQuery.getLimitSize());
    }

    @Test
    void searchListRejectsZeroAndNegativeLimitSize() {
        for (int stated : new int[] {0, -1}) {
            SearchListParams params = new SearchListParams();
            params.setLimitSize(stated);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> SearchListParams.convertParamsToFlexQuery(params));
        }
    }
}
