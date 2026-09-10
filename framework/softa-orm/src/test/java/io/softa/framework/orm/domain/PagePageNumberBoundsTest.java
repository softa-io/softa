package io.softa.framework.orm.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.constant.BaseConstant;

/**
 * `pageNumber` is documented as the CURRENT page, so a response must never name a page that does not
 * exist. Overshooting used to be echoed back untouched, giving a self-contradicting response —
 * pageNumber 2 alongside totalPages 1, totalCount 2 and no rows — which left every client to infer
 * "out of range" by comparing two fields, and one of them did not.
 *
 * <p>The clamp lands in {@code setTotalCount} rather than at the response boundary on purpose:
 * {@code JdbcServiceImpl#selectByPage} counts first and only then builds the page SQL, so the offset
 * is derived from the clamped value and the caller receives the last page's rows.
 */
class PagePageNumberBoundsTest {

    @Test
    void clampsAPageNumberPastTheEndToTheLastPage() {
        Page<Object> page = Page.of(2, 20);

        page.setTotalCount(2);

        Assertions.assertEquals(1, page.getTotalPages());
        Assertions.assertEquals(1, page.getPageNumber(), "page 2 of 1 does not exist");
    }

    @Test
    void clampsToTheLastPageNotToTheFirst() {
        // Landing on page 1 would throw away the reader's position; the last page is the nearest page
        // that exists to where they were.
        Page<Object> page = Page.of(9, 10);

        page.setTotalCount(25);

        Assertions.assertEquals(3, page.getTotalPages());
        Assertions.assertEquals(3, page.getPageNumber());
    }

    @Test
    void leavesAPageNumberInRangeAlone() {
        Page<Object> page = Page.of(2, 10);

        page.setTotalCount(25);

        Assertions.assertEquals(3, page.getTotalPages());
        Assertions.assertEquals(2, page.getPageNumber());
    }

    @Test
    void keepsTheFirstPageWhenThereIsNothingToPage() {
        // totalCount 0 short-circuits before setTotalCount in selectByPage, but the guard has to hold
        // if that ever changes: totalPages 0 must not clamp pageNumber to 0.
        Page<Object> page = Page.of(1, 10);

        page.setTotalCount(0);

        Assertions.assertEquals(0, page.getTotalPages());
        Assertions.assertEquals(1, page.getPageNumber());
    }

    @Test
    void stillCorrectsAPageNumberBelowOne() {
        // The lower bound is handled in the constructor and deliberately differently: 0 or negative is a
        // malformed request, while overshooting is what happens to a valid page when rows are deleted
        // underneath it.
        Assertions.assertEquals(BaseConstant.DEFAULT_PAGE_NUMBER, Page.of(0, 10).getPageNumber());
        Assertions.assertEquals(BaseConstant.DEFAULT_PAGE_NUMBER, Page.of(-5, 10).getPageNumber());
    }

    @Test
    void doesNotTouchCursorPagination() {
        // pageNumber is fixed and meaningless under cursor pagination.
        Page<Object> page = Page.ofCursorPage(10);

        page.setTotalCount(2);

        Assertions.assertEquals(BaseConstant.DEFAULT_PAGE_NUMBER, page.getPageNumber());
    }
}
