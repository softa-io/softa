package io.softa.starter.flow.compiler;

import java.util.ArrayList;
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
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node that is not a gateway tries its conditional edges by their priority, those without one after in drawing
 * order, and its default last: the compiler lists them so. A default given a priority before a conditional edge is
 * refused.
 */
class EdgePriorityCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void edgesAreListedByPriorityThoseWithoutAfterAndTheDefaultLast() {
        CompiledFlowDefinition compiled = compiler.compile(flow(
                edge("check-default", "check", "hr", null, null),
                edge("check-late", "check", "travel", "abroad == true", null),
                edge("check-second", "check", "finance", "amount > 100", 2),
                edge("check-first", "check", "ceo", "amount > 10000", 1)));

        assertEquals(List.of("check-first", "check-second", "check-late", "check-default"),
                compiled.getNodeIndex().get("check").getOutgoingEdgeIds());
    }

    @Test
    void aDefaultGivenAPriorityAheadOfAConditionalEdgeIsRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                edge("check-default", "check", "hr", null, 1),
                edge("check-finance", "check", "finance", "amount > 100", 2),
                edge("check-ceo", "check", "ceo", "amount > 10000", null),
                edge("check-travel", "check", "travel", "abroad == true", null)));

        assertEquals(List.of("DEFAULT_EDGE_NOT_LAST"), diagnostics.stream().map(CompileDiagnostic::code).toList());
        assertEquals("check-default", diagnostics.getFirst().edgeId());
        assertTrue(diagnostics.getFirst().message().contains("always tried last"), diagnostics.getFirst().message());
    }

    @Test
    void aDefaultWithoutAPriorityOrOneAfterTheRestCompiles() {
        assertEquals(List.of(), compiler.validate(flow(
                edge("check-default", "check", "hr", null, 9),
                edge("check-finance", "check", "finance", "amount > 100", 2),
                edge("check-ceo", "check", "ceo", "amount > 10000", 1),
                edge("check-travel", "check", "travel", "abroad == true", 3))));
    }

    /** START → check → one of ceo, finance, travel, hr → END, the check's edges as given. */
    private static DesignFlowDefinition flow(FlowGraphEdge... checkEdges) {
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(
                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                approval("check"), approval("ceo"), approval("finance"), approval("travel"), approval("hr"),
                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()));
        List<FlowGraphEdge> edges = new ArrayList<>(List.of(edge("start-check", "start", "check", null, null)));
        edges.addAll(List.of(checkEdges));
        for (String step : List.of("ceo", "finance", "travel", "hr")) {
            edges.add(edge(step + "-end", step, "end", null, null));
        }
        return DesignFlowDefinition.builder()
                .name("Expense claim")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("ExpenseClaim"))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }

    private static FlowGraphNode approval(String id) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(id)
                .config(Map.of("approverSource", Map.of("type", "Users", "userIds", List.of("2001")))).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target, String condition, Integer priority) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition)
                .priority(priority).build();
    }
}
