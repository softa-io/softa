package io.softa.starter.flow.runtime.descriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The palette offers each node type to the scenarios {@link FlowNodeType#getScenarios()} declares — the
 * same rule the compiler enforces, so the editor never offers a node the publish would refuse.
 */
class NodeDescriptorScenariosTest {

    @Test
    void theApprovalNodeIsOfferedToApprovalFlowsOnly() {
        FlowNodeDescriptor approval = new BuiltinNodeDescriptorProvider().getDescriptors().stream()
                .filter(d -> d.type() == FlowNodeType.APPROVAL)
                .findFirst().orElseThrow();

        assertEquals(Set.of(FlowScenario.APPROVAL), approval.allowedScenarios());
    }

    @Test
    void aTaskExecutorIsOfferedWhereItsNodeTypeIsAllowed() {
        TaskExecutor email = mock(TaskExecutor.class);
        when(email.getSupportedNodeType()).thenReturn(FlowNodeType.SEND_EMAIL);
        when(email.getName()).thenReturn("Send Email");

        FlowNodeDescriptor descriptor = descriptorOf(email);

        assertEquals(FlowNodeType.SEND_EMAIL.getScenarios(), descriptor.allowedScenarios());
        assertFalse(descriptor.allowedScenarios().contains(FlowScenario.APPROVAL), "an approval flow sends nothing");
    }

    /** Every task node names the variable its result goes to; two nodes writing {@code result} would overwrite. */
    @Test
    void everyTaskNodeTakesAnOutputVariableAfterItsExecutorsFields() {
        TaskExecutor lookup = mock(TaskExecutor.class);
        when(lookup.getSupportedNodeType()).thenReturn(FlowNodeType.GET_RECORD);
        when(lookup.getName()).thenReturn("Get Record");
        when(lookup.getConfigSchema()).thenReturn(Map.of("modelName", Map.of("type", "model")));

        Map<String, Object> schema = descriptorOf(lookup).configSchema();

        assertEquals(List.of("modelName", "outputVariable"), List.copyOf(schema.keySet()));
        assertEquals(Map.of("type", "string", "label", "Output variable"), schema.get("outputVariable"));
    }

    @Test
    void anExecutorsOwnOutputVariableFieldIsKept() {
        TaskExecutor script = mock(TaskExecutor.class);
        when(script.getSupportedNodeType()).thenReturn(FlowNodeType.TRANSFORM);
        when(script.getName()).thenReturn("Transform");
        Map<String, Object> own = Map.of("type", "string", "label", "Result as");
        when(script.getConfigSchema()).thenReturn(Map.of("outputVariable", own));

        assertSame(own, descriptorOf(script).configSchema().get("outputVariable"));
    }

    private static FlowNodeDescriptor descriptorOf(TaskExecutor executor) {
        return new FlowNodeDescriptorRegistry(new BuiltinNodeDescriptorProvider(), List.of(executor)).list().stream()
                .filter(d -> d.type() == executor.getSupportedNodeType())
                .findFirst().orElseThrow();
    }
}
