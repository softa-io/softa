package io.softa.framework.orm.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.jdbc.database.SqlBuilderFactory;
import io.softa.framework.orm.jdbc.database.SqlParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A page read again for the next page must not keep the rows of the page before.
 *
 * <p>{@code selectByPage} returned early on an empty result without touching the page, so a loop that
 * reached the end handled the last full page a second time and, since that page still looked full, went
 * on asking for the page after it.
 */
class SelectByPageTest {

    private static final String MODEL = "Employee";

    private final JdbcProxy jdbcProxy = mock(JdbcProxy.class);
    private final JdbcServiceImpl<Long> jdbcService = new JdbcServiceImpl<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jdbcService, "jdbcProxy", jdbcProxy);
    }

    @Test
    void anEmptyResultEmptiesThePage() {
        try (MockedStatic<SqlBuilderFactory> sql = mockStatic(SqlBuilderFactory.class)) {
            sql.when(() -> SqlBuilderFactory.buildPageSql(eq(MODEL), any(), any()))
                    .thenReturn(new SqlParams("SELECT id FROM employee"));
            when(jdbcProxy.queryForList(eq(MODEL), any())).thenReturn(rows(2), new ArrayList<>());
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setConvertType(ConvertType.ORIGINAL);
            Page<Map<String, Object>> page = Page.ofCursorPage(2);

            jdbcService.selectByPage(MODEL, flexQuery, page);
            assertThat(page.toNext()).isTrue();
            jdbcService.selectByPage(MODEL, flexQuery, page);

            assertThat(page.getRows()).isEmpty();
            assertThat(page.toNext()).isFalse();
        }
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            rows.add(Map.of("id", id));
        }
        return rows;
    }
}
