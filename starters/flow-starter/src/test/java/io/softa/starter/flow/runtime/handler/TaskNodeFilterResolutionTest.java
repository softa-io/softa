package io.softa.starter.flow.runtime.handler;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;
import io.softa.starter.flow.runtime.task.builtin.DefaultTaskExecutorRegistry;
import io.softa.starter.flow.runtime.task.builtin.DeleteDataTaskExecutor;
import io.softa.starter.flow.runtime.task.builtin.ExtractTransformTaskExecutor;
import io.softa.starter.flow.runtime.task.builtin.GetDataTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the typed-Filters/Orders dead-branch bug: in production {@code config.task.input}
 * is bound from JSON as {@code Map<String,Object>}, so a {@code filters} value arrives as a raw
 * {@code List} (e.g. {@code ["status","=","{{ wantedStatus }}"]}), never a typed {@link Filters}.
 * {@link TaskNodeExecutionHandler} passes lists through unchanged, so the executors' old
 * {@code instanceof Filters} gate was always false in production and the configured filter was
 * silently dropped (reads went unfiltered; filters-only writes became silent no-ops).
 * <p>
 * These tests drive a raw-list filter through the real handler → executor → {@link ModelService}
 * chain (NOT a hand-injected typed Filters, which is what masked the bug in the per-executor tests)
 * and assert the filter actually reaches the model service, coerced and placeholder-resolved.
 */
class TaskNodeFilterResolutionTest {

    private static void wire(Object executor, ModelService<?> modelService) throws Exception {
        Field field = executor.getClass().getDeclaredField("modelService");
        field.setAccessible(true);
        field.set(executor, modelService);
    }

    private static CompiledFlowNode taskNode(FlowNodeType type, Map<String, Object> input) {
        return CompiledFlowNode.builder()
                .nodeId("n1")
                .type(type)
                .parsedConfig(TaskNodeConfig.builder().input(input).build())
                .build();
    }

    @Test
    void getRecordAppliesRawListFilterResolvedAgainstVariables() throws Exception {
        ModelService<Serializable> modelService = mock(ModelService.class);
        GetDataTaskExecutor executor = new GetDataTaskExecutor();
        wire(executor, modelService);
        TaskNodeExecutionHandler handler =
                new TaskNodeExecutionHandler(new DefaultTaskExecutorRegistry(List.of(executor)));

        when(modelService.searchList(eq("Order"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 1L)));

        CompiledFlowNode node = taskNode(FlowNodeType.GET_RECORD, Map.of(
                "modelName", "Order",
                "getDataType", "MultiRows",
                "fields", List.of("id"),
                "filters", List.of("status", "=", "{{ wantedStatus }}")));
        FlowVariableContext ctx = new FlowVariableContext(Map.of("wantedStatus", "ACTIVE"), Map.of());

        handler.execute(node, ctx);

        ArgumentCaptor<FlexQuery> captor = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq("Order"), captor.capture());
        Filters applied = captor.getValue().getFilters();
        assertFalse(Filters.isEmpty(applied), "raw-list filter must reach the model service, not be dropped");
        // The {{ wantedStatus }} placeholder must be resolved against the flow variables, not passed literally.
        assertEquals("ACTIVE", applied.getFilterUnit().getValue());
    }

    @Test
    void transformResolvesCollectionVariableThroughHandler() {
        ExtractTransformTaskExecutor executor = new ExtractTransformTaskExecutor();
        TaskNodeExecutionHandler handler =
                new TaskNodeExecutionHandler(new DefaultTaskExecutorRegistry(List.of(executor)));

        CompiledFlowNode node = taskNode(FlowNodeType.TRANSFORM, Map.of(
                "collectionVariable", "{{ rows }}",
                "itemKey", "deptId"));
        FlowVariableContext ctx = new FlowVariableContext(
                Map.of("rows", List.of(Map.of("deptId", 10L), Map.of("deptId", 20L))), Map.of());

        Map<String, Object> outputs = handler.execute(node, ctx).outputs();

        // Regression: the handler used to pre-resolve {{ rows }} to the list, which the executor then
        // stringified — parsePlaceholder failed and the extraction returned empty. With no generic
        // pre-resolution the raw placeholder reaches the executor and resolves against the variables.
        assertInstanceOf(Set.class, outputs.get("result"));
        assertEquals(Set.of(10L, 20L), outputs.get("result"));
    }

    @Test
    void deleteRecordWithFiltersOnlyIsNotASilentNoop() throws Exception {
        ModelService<Serializable> modelService = mock(ModelService.class);
        DeleteDataTaskExecutor executor = new DeleteDataTaskExecutor();
        wire(executor, modelService);
        TaskNodeExecutionHandler handler =
                new TaskNodeExecutionHandler(new DefaultTaskExecutorRegistry(List.of(executor)));

        when(modelService.deleteByFilters(eq("Order"), any(Filters.class))).thenReturn(true);

        // No pkVariable — only filters. The old dead branch produced an empty Filters → isEmpty guard
        // → deleteByFilters never called (silent no-op). The fix must build a real filter and delete.
        CompiledFlowNode node = taskNode(FlowNodeType.DELETE_RECORD, Map.of(
                "modelName", "Order",
                "filters", List.of("status", "=", "CANCELLED")));

        Map<String, Object> result = handler.execute(node, new FlowVariableContext()).outputs();

        ArgumentCaptor<Filters> captor = ArgumentCaptor.forClass(Filters.class);
        verify(modelService).deleteByFilters(eq("Order"), captor.capture());
        assertFalse(Filters.isEmpty(captor.getValue()), "filters-only delete must build a real filter");
        assertEquals(true, result.get("deleted"));
    }
}
