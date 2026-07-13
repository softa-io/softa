package io.softa.starter.flow.runtime.handler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.nodeconfig.CreateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;
import io.softa.starter.flow.runtime.task.builtin.DefaultTaskExecutorRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Locks the P2 typed-config contract: for a node type registered in {@code TaskConfigTypes}
 * the handler hands the executor the RAW, unresolved input plus a parsed config DTO — it does
 * NOT generically pre-resolve the row template. That is what lets the executor's type-aware
 * {@code VariableResolver} own resolution (previously the handler pre-resolved the template map,
 * so the field-type-aware branch in VariableResolver was dead).
 */
class TaskNodeTypedConfigTest {

    /** Captures the request the handler builds, so the test can assert what the executor receives. */
    private static final class CapturingExecutor implements TaskExecutor {
        private TaskExecutionRequest captured;

        @Override
        public FlowNodeType getSupportedNodeType() {
            return FlowNodeType.CREATE_RECORD;
        }

        @Override
        public String getExecutor() {
            return "capture";
        }

        @Override
        public String getName() {
            return "capture";
        }

        @Override
        public String getDescription() {
            return "test capture";
        }

        @Override
        public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
            this.captured = request;
            return Map.of();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void typedNodeReceivesRawTemplateAndParsedConfig() {
        CapturingExecutor executor = new CapturingExecutor();
        TaskNodeExecutionHandler handler =
                new TaskNodeExecutionHandler(new DefaultTaskExecutorRegistry(List.of(executor)));

        CompiledFlowNode node = CompiledFlowNode.builder()
                .nodeId("n1")
                .type(FlowNodeType.CREATE_RECORD)
                .parsedConfig(TaskNodeConfig.builder()
                        .input(Map.of(
                                "modelName", "Order",
                                "rowTemplate", Map.of("name", "{{ customerName }}")))
                        .build())
                .build();
        FlowVariableContext ctx = new FlowVariableContext(Map.of("customerName", "Alice"), Map.of());

        handler.execute(node, ctx);

        // The row-template placeholder reaches the executor unresolved — the handler did not pre-resolve it.
        Map<String, Object> rowTemplate =
                (Map<String, Object>) executor.captured.getInput().get("rowTemplate");
        assertEquals("{{ customerName }}", rowTemplate.get("name"));

        // A parsed typed config DTO is supplied for the executor to consume directly.
        assertInstanceOf(CreateDataConfig.class, executor.captured.getConfig());
        assertEquals("Order", ((CreateDataConfig) executor.captured.getConfig()).getModelName());
    }
}
