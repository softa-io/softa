package io.softa.starter.flow.runtime.task.builtin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link QueryRecordsTaskExecutor} — paginated model query that returns
 * rows plus pagination metadata.
 */
class QueryRecordsTaskExecutorTest {

    @SuppressWarnings("rawtypes")
    private ModelService modelService;
    private QueryRecordsTaskExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        modelService = mock(ModelService.class);
        executor = new QueryRecordsTaskExecutor();
        // QueryRecordsTaskExecutor uses field injection (@Autowired private ModelService);
        // there is no constructor, so set the collaborator directly via reflection.
        Field field = QueryRecordsTaskExecutor.class.getDeclaredField("modelService");
        field.setAccessible(true);
        field.set(executor, modelService);
    }

    @Test
    void reportsSupportedNodeType() {
        assertSame(FlowNodeType.QUERY_RECORDS, executor.getSupportedNodeType());
        assertEquals("QueryRecords", executor.getExecutor());
    }

    @SuppressWarnings("unchecked")
    @Test
    void returnsRowsAndPaginationMetadata() {
        Page<Map<String, Object>> resultPage = Page.of(1, 50);
        resultPage.setRows(List.of(
                Map.of("name", "Alice", "deptId", 10L),
                Map.of("name", "Bob", "deptId", 20L)));
        resultPage.setTotalCount(2L);

        when(modelService.searchPage(eq("Employee"), any(FlexQuery.class), any(Page.class)))
                .thenReturn(resultPage);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.QUERY_RECORDS)
                .input(Map.of(
                        "modelName", "Employee",
                        "fields", List.of("name", "deptId"),
                        "page", 1,
                        "pageSize", 50))
                .build();

        Map<String, Object> out = executor.execute(request, Map.of());

        assertEquals(resultPage.getRows(), out.get("rows"));
        assertEquals(2, out.get("totalCount"));
        assertEquals(1, out.get("totalPages"));
        assertEquals(1, out.get("pageNumber"));
        assertEquals(50, out.get("pageSize"));

        verify(modelService).searchPage(eq("Employee"), any(FlexQuery.class), any(Page.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void defaultsPageAndPageSizeWhenOmitted() {
        Page<Map<String, Object>> resultPage = Page.of(1, 50);
        resultPage.setRows(List.of());
        resultPage.setTotalCount(0L);

        when(modelService.searchPage(eq("Employee"), any(FlexQuery.class), any(Page.class)))
                .thenReturn(resultPage);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.QUERY_RECORDS)
                .input(Map.of("modelName", "Employee"))
                .build();

        Map<String, Object> out = executor.execute(request, Map.of());

        // Page.of(1, 50) is the executor's fallback when page/pageSize are absent.
        assertEquals(1, out.get("pageNumber"));
        assertEquals(50, out.get("pageSize"));
        assertEquals(0, out.get("totalCount"));
    }

    @Test
    void rejectsMissingModelName() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.QUERY_RECORDS)
                .input(Map.of("fields", List.of("name")))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("input.modelName"));
    }

    @Test
    void rejectsBlankModelName() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.QUERY_RECORDS)
                .input(Map.of("modelName", "   "))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }
}
