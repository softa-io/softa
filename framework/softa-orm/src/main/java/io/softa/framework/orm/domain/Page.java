package io.softa.framework.orm.domain;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.utils.Assert;

/**
 * Paging object
 * @param <T>
 */
@Getter
public class Page<T> implements Serializable {

    // Page size, the default is 50, and the page size is between [1, MAX_BATCH_SIZE]
    private final int pageSize;

    // Current page number, counting from 1
    private int pageNumber;

    /**
     * A page for walking a query to its end without counting it, the way a batch job reads:
     * {@code do { ... } while (page.toNext())}, moving on while each page comes back full.
     * <p>
     * As on every page, the order is made total so that consecutive pages neither repeat nor skip a row: the
     * query's own order (or the model's {@code defaultOrder}, or {@code id DESC}) is followed by the model's
     * physical primary key in the direction of the first sort — {@code sliceId} on a timeline model, whose
     * slices share their record's {@code id}, and {@code id} otherwise.
     * <p>
     * The pages are read by offset. A keyset condition ({@code WHERE id > ?}) would spare a deep page from
     * reading the rows before it, but it only fits a query ordered by the key alone, and these pages keep
     * the caller's order.
     */
    @JsonIgnore
    private final boolean cursorPage;

    // Whether to perform a count query first, return directly when count is 0, default is true
    @JsonIgnore
    @Setter
    private boolean count;

    // Total number of pages, calculated by (total + pageSize - 1) / pageSize
    private int totalPages;

    // Total number of rows
    private int totalCount;

    private List<T> rows = Collections.emptyList();

    @JsonIgnore
    private int currentPageSize;

    /**
     * Default first page, page size is 50, and ensure that pageSize is between [1, MAX_BATCH_SIZE]
     * <p>
     * An <b>omitted</b> {@code pageSize} takes the default; a <b>stated</b> one is honoured or
     * rejected, never quietly replaced. Silently turning `pageSize: 0` into 50 answered a request
     * nobody made — the caller asked for no rows and got a full page, and a caller that had computed
     * its size wrongly (an off-by-one, an empty id list) learned nothing about it.
     *
     * @param pageNumber Current page number
     * @param pageSize Page size, null for the default; must be within [1, MAX_BATCH_SIZE] when stated
     * @param cursorPage Whether to walk the query in a total order without counting it
     * @param count Whether to perform a count query first
     */
    public Page(Integer pageNumber, Integer pageSize, boolean cursorPage, boolean count) {
        // A cursor page is read by its number too, so a copy made to run the query (the typed searchPage
        // makes one) has to ask for the same page rather than the first one again.
        this.pageNumber = pageNumber == null || pageNumber < 1 ? BaseConstant.DEFAULT_PAGE_NUMBER : pageNumber;
        if (pageSize == null) {
            this.pageSize = BaseConstant.DEFAULT_PAGE_SIZE;
        } else {
            Assert.isTrue(pageSize > 0,
                    "Page size must be a positive number, but got {0}.", pageSize);
            Assert.isTrue(pageSize <= BaseConstant.MAX_BATCH_SIZE,
                    "Page size {0} cannot exceed the maximum limit: {1}", pageSize, BaseConstant.MAX_BATCH_SIZE);
            this.pageSize = pageSize;
        }
        this.cursorPage = cursorPage;
        this.count = count;
    }

    /**
     * A cursor page of the default size 50: total order, no count query.
     *
     * @return Cursor page, on its first page
     * @param <T> Generic type
     */
    public static <T> Page<T> ofCursorPage() {
        return new Page<>(null, BaseConstant.DEFAULT_PAGE_SIZE, true, false);
    }

    /**
     * A cursor page of the given size: total order, no count query.
     *
     * @return Cursor page, on its first page
     * @param <T> Generic type
     */
    public static <T> Page<T> ofCursorPage(Integer pageSize) {
        return new Page<>(null, pageSize, true, false);
    }

    /**
     * A cursor page of the given size, on the given page: total order, no count query. For a client that
     * asks for one page at a time, such as a picker loading more candidates as its list scrolls.
     *
     * @param pageNumber Page number, counting from 1; null or below 1 for the first
     * @param pageSize Page size, null for the default
     * @return Cursor page, on the given page
     * @param <T> Generic type
     */
    public static <T> Page<T> ofCursorPage(Integer pageNumber, Integer pageSize) {
        return new Page<>(pageNumber, pageSize, true, false);
    }

    /**
     * Offset-based paging object constructor, default count total rows.
     * Typically used for client-side API paging queries.
     *
     * @param pageNumber Current page number
     * @param pageSize Page size
     * @return Page object
     * @param <T> Generic type
     */
    public static <T> Page<T> of(Integer pageNumber, Integer pageSize) {
        return new Page<>(pageNumber, pageSize, false, true);
    }

    /**
     * Offset-based paging object constructor with specified count parameter.
     * Compatible with traditional offset-based paging queries.
     *
     * @param pageNumber Current page number
     * @param pageSize Page size
     * @param count Whether to perform a count query first
     * @return Page object
     * @param <T> Generic type
     */
    public static <T> Page<T> of(Integer pageNumber, Integer pageSize, boolean count) {
        return new Page<>(pageNumber, pageSize, false, count);
    }

    /**
     * When setting the total, calculate the total number of pages at the same time.
     *
     * @param count Total number of rows
     */
    public void setTotalCount(long count) {
        this.totalCount = Math.toIntExact(count);
        this.totalPages = (totalCount + pageSize - 1) / pageSize;
    }

    /**
     * Set the rows of the current page and calculate the current page size.
     *
     * @param rows List of rows
     * @return Page object
     */
    public Page<T> setRows(List<T> rows) {
        this.rows = rows;
        if (rows != null) {
            this.currentPageSize = rows.size();
        }
        return this;
    }

    /**
     * Moves to the next page and returns true when there may be one, for {@code do { ... } while (page.toNext())}.
     * <ul>
     *   <li>Cursor page: while the page just read came back full. A last page that happens to be full is
     *   followed by one read that comes back empty.</li>
     *   <li>Offset page: while the page number stays within {@code totalPages}, which needs the count.</li>
     * </ul>
     * A loop that reads every page through one {@link FlexQuery} restores its own filters before each read:
     * {@code ModelService.searchPage} writes the access-scoped filters back into the query it is given, so
     * a query reused as it was carries one more copy of the scope into every page after the first.
     *
     * @return true when the next page is to be read
     */
    public boolean toNext() {
        if (cursorPage) {
            boolean full = currentPageSize == pageSize;
            if (full) {
                pageNumber++;
            }
            return full;
        } else {
            pageNumber ++;
            return pageNumber > 1 && pageNumber <= totalPages;
        }
    }
}
