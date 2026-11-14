package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overdue reminders are an approval flow's setting, not a step's: {@code remindAfterHours} is at least 1 and belongs
 * to Approval flows. A {@code timeout} a step was saved with before is ignored — it compiles, and decides nothing.
 */
class ApprovalTimeoutCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void remindAfterHoursIsAtLeastOne() {
        assertTrue(has(approval(0, Map.of()), "INVALID_REMIND_AFTER_HOURS"));
        assertFalse(has(approval(24, Map.of()), "INVALID_REMIND_AFTER_HOURS"));
        assertEquals(24, compiler.compile(approval(24, Map.of())).getRemindAfterHours());
    }

    @Test
    void remindAfterHoursBelongsToApprovalFlows() {
        DesignFlowDefinition automation = DesignFlowDefinition.builder()
                .name("Nightly")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .remindAfterHours(24)
                .graph(graph(List.of()))
                .build();

        assertTrue(has(automation, "APPROVAL_SETTING_OUTSIDE_APPROVAL"));
    }

    @Test
    void aTimeoutAStepWasSavedWithBeforeIsIgnored() {
        DesignFlowDefinition saved = approval(null, Map.of("timeout",
                Map.of("timeoutHours", 24, "timeoutStrategy", "AutoApprove", "escalateToUserId", "2009")));

        List<CompileDiagnostic> diagnostics = compiler.validate(saved);
        CompiledFlowDefinition compiled = compiler.compile(saved);

        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
        assertEquals(null, compiled.getRemindAfterHours(), "a step's old deadline sets no reminder");
    }

    private boolean has(DesignFlowDefinition definition, String code) {
        return compiler.validate(definition).stream().anyMatch(d -> code.equals(d.code()));
    }

    private static DesignFlowDefinition approval(Integer remindAfterHours, Map<String, Object> stepExtras) {
        Map<String, Object> config = new java.util.LinkedHashMap<>(stepExtras);
        config.put("approverSources", List.of(Map.of("type", "Users", "userIds", List.of("manager"))));
        return DesignFlowDefinition.builder()
                .name("Timeouts")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .remindAfterHours(remindAfterHours)
                .graph(graph(List.of(FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL)
                        .label("Manager").config(config).build())))
                .build();
    }

    /** start → {@code middle} → end. */
    private static FlowGraphDocument graph(List<FlowGraphNode> middle) {
        List<FlowGraphNode> nodes = new java.util.ArrayList<>();
        List<FlowGraphEdge> edges = new java.util.ArrayList<>();
        nodes.add(FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build());
        String previous = "start";
        for (FlowGraphNode node : middle) {
            nodes.add(node);
            edges.add(FlowGraphEdge.builder().id("to-" + node.getId()).source(previous).target(node.getId()).build());
            previous = node.getId();
        }
        nodes.add(FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build());
        edges.add(FlowGraphEdge.builder().id("to-end").source(previous).target("end").build());
        return FlowGraphDocument.builder().nodes(nodes).edges(edges).build();
    }
}
