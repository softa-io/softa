package io.softa.framework.orm.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cursor page walks a query in a stable order without counting it: {@code do { ... } while (page.toNext())}
 * goes on while each page comes back full.
 *
 * <p>It used to stay on page one. {@code toNext()} recorded the last id for a {@code WHERE id > ?} that no
 * query applied, and never moved the page on, so a first page that came back full was read again and again:
 * an export of 10,000 rows or more, and a recompute or encryption pass over 1,000, never ended.
 */
class CursorPageTest {

    @Test
    void movesToTheNextPageWhileThePageIsFull() {
        Page<Map<String, Object>> page = Page.ofCursorPage(2);
        page.setRows(rows(2));

        assertThat(page.toNext()).isTrue();
        assertThat(page.getPageNumber()).isEqualTo(2);
    }

    @Test
    void stopsAtAShortPage() {
        Page<Map<String, Object>> page = Page.ofCursorPage(2);
        page.setRows(rows(1));

        assertThat(page.toNext()).isFalse();
        assertThat(page.getPageNumber()).isEqualTo(1);
    }

    @Test
    void stopsAtTheEmptyPageAfterAFullLastPage() {
        Page<Map<String, Object>> page = Page.ofCursorPage(2);
        page.setRows(rows(2));
        page.toNext();
        page.setRows(List.of());

        assertThat(page.toNext()).isFalse();
    }

    /**
     * The typed {@code searchPage} and the ES change log query through a copy of the page: the copy has to
     * ask for the same page, not the first one again.
     */
    @Test
    void aCopyAsksForTheSamePage() {
        Page<Object> copy = new Page<>(3, 10, true, false);

        assertThat(copy.getPageNumber()).isEqualTo(3);
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            rows.add(Map.of("id", id));
        }
        return rows;
    }
}
