package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
 * Tests for {@link UpdateDataTaskExecutor} — updates rows via {@link ModelService}
 * identified by a primary key variable and/or filters.
 */
class UpdateDataTaskExecutorTest {

    @SuppressWarnings("unchecked")
    private ModelService<? extends Serializable> modelService = mock(ModelService.class);

    private UpdateDataTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new UpdateDataTaskExecutor();
        // modelService is field-injected (@Autowired) in production; wire it manually for the unit test.
        ReflectionTestUtils.setField(executor, "modelService", modelService);
    }

    @Test
    void supportedNodeTypeIsUpdateRecord() {
        assertEquals(FlowNodeType.UPDATE_RECORD, executor.getSupportedNodeType());
        assertEquals("UpdateData", executor.getExecutor());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatesRowsResolvedByPkVariable() {
        when(((ModelService<Serializable>) modelService)
                .updateByFilter(eq("Employee"), any(Filters.class), eq(Map.of("name", "Bob"))))
                .thenReturn(3);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.UPDATE_RECORD)
                .input(Map.of(
                        "modelName", "Employee",
                        "pkVariable", "{{ employeeId }}",
                        "rowTemplate", Map.of("name", "Bob")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of("employeeId", 100L));

        assertEquals(3, result.get("affected"));

        // The resolved filter must target the id of the resolved pk variable (non-empty).
        ArgumentCaptor<Filters> filtersCaptor = ArgumentCaptor.forClass(Filters.class);
        verify((ModelService<Serializable>) modelService)
                .updateByFilter(eq("Employee"), filtersCaptor.capture(), eq(Map.of("name", "Bob")));
        assertFalse(Filters.isEmpty(filtersCaptor.getValue()));
    }

    @Test
    void returnsZeroAffectedWhenNoPkAndNoFilters() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.UPDATE_RECORD)
                .input(Map.of(
                        "modelName", "Employee",
                        "rowTemplate", Map.of("name", "Bob")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(0, result.get("affected"));
        // No identifying condition -> the model layer must not be touched.
        verify(modelService, never()).updateByFilter(any(), any(), any());
    }

    @Test
    void returnsZeroAffectedWhenPkVariableResolvesToNull() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("employeeId", null);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.UPDATE_RECORD)
                .input(Map.of(
                        "modelName", "Employee",
                        "pkVariable", "{{ employeeId }}",
                        "rowTemplate", Map.of("name", "Bob")))
                .build();

        Map<String, Object> result = executor.execute(request, variables);

        assertEquals(0, result.get("affected"));
        verify(modelService, never()).updateByFilter(any(), any(), any());
    }

    @Test
    void throwsWhenModelNameMissing() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.UPDATE_RECORD)
                .input(Map.of("rowTemplate", Map.of("name", "Bob")))
                .build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
        assertEquals(true, ex.getMessage().contains("input.modelName"));
    }

    @Test
    void throwsWhenRowTemplateMissing() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.UPDATE_RECORD)
                .input(Map.of("modelName", "Employee"))
                .build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
        assertEquals(true, ex.getMessage().contains("input.rowTemplate"));
    }
}
