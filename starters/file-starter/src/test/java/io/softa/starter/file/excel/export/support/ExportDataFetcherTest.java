package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportDataFetcherTest {

    private static final String MODEL = "Employee";

    private final ExportDataFetcher fetcher = new ExportDataFetcher();

    @SuppressWarnings("rawtypes")
    private final ModelService modelService = mock(ModelService.class);
    private final PermissionService permissionService = mock(PermissionService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fetcher, "modelService", modelService);
        ReflectionTestUtils.setField(fetcher, "permissionService", permissionService);
        when(permissionService.hasModelExportGrant(MODEL)).thenReturn(true);
    }

    /**
     * An export of more rows than one page holds reads every page once, each from the export's own filters.
     *
     * <p>It used to read the first page forever, so an export of 10,000 rows or more never finished. And
     * the read writes the access scope back into the query it is given: a query reused as it was would
     * carry one more copy of the scope into every page after the first.
     */
    @Test
    void readsEveryPageOnceFromTheExportsOwnFilters() {
        int pageSize = BaseConstant.MAX_BATCH_SIZE;
        List<List<Map<String, Object>>> pages = List.of(rows(0, pageSize), rows(pageSize, 1));
        Filters exportFilters = new Filters().eq("status", "Active");
        FlexQuery query = new FlexQuery(List.of("id"), exportFilters);
        List<Filters> filtersRead = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        when(modelService.searchPage(eq(MODEL), any(), any())).thenAnswer(invocation -> {
            if (reads.incrementAndGet() > pages.size()) {
                throw new AssertionError("Read " + reads + " pages, but there are only " + pages.size());
            }
            FlexQuery read = invocation.getArgument(1);
            filtersRead.add(read.getFilters());
            // What ModelServiceImpl.searchPage does: the scoped filters are written back into the query.
            read.setFilters(Filters.and(read.getFilters(), new Filters().eq("companyId", 1L)));
            Page<Map<String, Object>> page = invocation.getArgument(2);
            return page.setRows(pages.get(page.getPageNumber() - 1));
        });

        List<Map<String, Object>> exported = fetcher.fetchRows(MODEL, null, query);

        assertThat(exported).hasSize(pageSize + 1);
        assertThat(filtersRead).containsExactly(exportFilters, exportFilters);
    }

    private static List<Map<String, Object>> rows(int from, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = from + 1; id <= from + count; id++) {
            rows.add(Map.of("id", id));
        }
        return rows;
    }
}
