package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeleteDataTaskExecutor} — deletes rows by primary-key variable
 * and/or filters via {@link ModelService#deleteByFilters}.
 */
class DeleteDataTaskExecutorTest {

    @SuppressWarnings("unchecked")
    private final ModelService<? extends Serializable> modelService = mock(ModelService.class);
    private DeleteDataTaskExecutor executor;

    /** Inject the mocked ModelService into the @Autowired private field (no constructor/setter exists). */
    @BeforeEach
    void setUp() throws Exception {
        executor = new DeleteDataTaskExecutor();
        Field field = DeleteDataTaskExecutor.class.getDeclaredField("modelService");
        field.setAccessible(true);
        field.set(executor, modelService);
    }

    @Test
    void supportsDeleteRecordNodeType() {
        assertEquals(FlowNodeType.DELETE_RECORD, executor.getSupportedNodeType());
        assertEquals("DeleteData", executor.getExecutor());
    }

    @Test
    void deletesByFiltersHappyPath() {
        when(modelService.deleteByFilters(eq("DemoModel"), any(Filters.class))).thenReturn(true);

        // Filters arrive from flow JSON as a raw list (["field","op",value]), coerced by the executor.
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.DELETE_RECORD)
                .input(Map.of("modelName", "DemoModel", "filters", List.of("active", "=", true)))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(true, result.get("deleted"));
        verify(modelService).deleteByFilters(eq("DemoModel"), any(Filters.class));
    }

    @Test
    void deletesByPrimaryKeyVariable() {
        when(modelService.deleteByFilters(eq("DemoModel"), any(Filters.class))).thenReturn(true);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.DELETE_RECORD)
                .input(Map.of("modelName", "DemoModel", "pkVariable", "{{ ids }}"))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of("ids", List.of(1L, 2L)));

        assertEquals(true, result.get("deleted"));
        verify(modelService).deleteByFilters(eq("DemoModel"), any(Filters.class));
    }

    @Test
    void returnsNotDeletedWhenNoPkVariableAndNoFilters() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.DELETE_RECORD)
                .input(Map.of("modelName", "DemoModel"))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(false, result.get("deleted"));
        verify(modelService, never()).deleteByFilters(any(), any());
    }

    @Test
    void returnsNotDeletedWhenPkVariableResolvesToEmptyCollection() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.DELETE_RECORD)
                .input(Map.of("modelName", "DemoModel", "pkVariable", "{{ ids }}"))
                .build();

        // "ids" present in variables but null -> resolved to an empty id collection -> empty filters
        HashMap<String, Object> variables = new HashMap<>();
        variables.put("ids", null);

        Map<String, Object> result = executor.execute(request, variables);

        assertEquals(false, result.get("deleted"));
        verify(modelService, never()).deleteByFilters(any(), any());
    }

    @Test
    void throwsWhenModelNameMissing() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.DELETE_RECORD)
                .input(Map.of("pkVariable", "{{ ids }}"))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertFalse(ex.getMessage() == null);
    }

    @Test
    void throwsWhenModelNameBlank() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.DELETE_RECORD)
                .input(Map.of("modelName", "   "))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }
}
