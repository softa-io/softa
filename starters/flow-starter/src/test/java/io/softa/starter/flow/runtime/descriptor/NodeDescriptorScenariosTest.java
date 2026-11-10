package io.softa.starter.flow.runtime.descriptor;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        FlowNodeDescriptor descriptor = new TaskExecutorDescriptorProvider(List.of(email)).getDescriptors().getFirst();

        assertEquals(FlowNodeType.SEND_EMAIL.getScenarios(), descriptor.allowedScenarios());
        assertFalse(descriptor.allowedScenarios().contains(FlowScenario.APPROVAL), "an approval flow sends nothing");
    }
}
