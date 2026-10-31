package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An approval node whose config holds a value it cannot be read with — the constant names an earlier designer
 * offered — is reported against that field, and the rest of the validation still answers.
 */
class ApprovalConfigUnreadableTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void aConstantNameIsReportedOnItsField() {
        List<CompileDiagnostic> diagnostics = assertDoesNotThrow(() -> compiler.validate(flowWithConfig(Map.of(
                "approverSource", Map.of("type", "Supervisor", "relativeTo", "SUBJECT", "level", 1),
                "emptyApproverStrategy", "ERROR",
                "approvalMode", "ANY_ONE"))));

        CompileDiagnostic unreadable = diagnostics.stream()
                .filter(d -> "UNREADABLE_CONFIG".equals(d.code()))
                .findFirst().orElseThrow();
        assertEquals("approval", unreadable.nodeId());
        assertTrue(List.of("config.emptyApproverStrategy", "config.approvalMode").contains(unreadable.field()),
                "the diagnostic names the field it could not read: " + unreadable.field());
        assertTrue(unreadable.message().startsWith("Approval node 'approval' cannot read config."),
                unreadable.message());
    }

    @Test
    void theJsonValuesAreRead() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flowWithConfig(Map.of(
                "approverSource", Map.of("type", "Supervisor", "relativeTo", "SUBJECT", "level", 1),
                "emptyApproverStrategy", "Error",
                "approvalMode", "AnyOne",
                "rejectMode", "AnyOne")));

        assertTrue(diagnostics.isEmpty(), diagnostics::toString);
    }

    private static DesignFlowDefinition flowWithConfig(Map<String, Object> config) {
        return DesignFlowDefinition.builder()
                .name("Unreadable Approval Test")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(config)
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()
                        ))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()
                        ))
                        .build())
                .build();
    }
}
