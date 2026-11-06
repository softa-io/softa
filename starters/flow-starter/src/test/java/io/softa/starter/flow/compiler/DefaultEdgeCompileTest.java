package io.softa.starter.flow.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node that chooses among several edges by their conditions has a default, so a request no condition fits has
 * somewhere to go (zingkey-hcm#2382): without one it would stop there — its submission refused or, after an approval,
 * the instance failed. Publishing refuses the gap. A node whose only edge has a condition is not one: a request that
 * does not fit ends its branch there.
 */
class DefaultEdgeCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void conditionalEdgesWithoutADefaultAreRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                edge("check-finance", "check", "finance", "totalAmount > 100"),
                edge("check-ceo", "check", "ceo", "totalAmount > 1000")));

        assertEquals(List.of("NO_DEFAULT_EDGE"), diagnostics.stream().map(CompileDiagnostic::code).toList());
        assertEquals("check", diagnostics.getFirst().nodeId());
        assertTrue(diagnostics.getFirst().message().contains("no default route"), diagnostics.getFirst().message());
    }

    @Test
    void aDefaultBesideTheConditionalEdgesCompiles() {
        assertEquals(List.of(), compiler.validate(flow(
                edge("check-finance", "check", "finance", "totalAmount > 100"),
                edge("check-ceo", "check", "ceo", "totalAmount > 1000"),
                edge("check-default", "check", "hr", null))));
    }

    @Test
    void aNodeWhoseOnlyEdgeHasAConditionCompiles() {
        assertEquals(List.of(), compiler.validate(flow(
                edge("check-finance", "check", "finance", "totalAmount > 100"))));
    }

    /** START → check → the steps its edges lead to → END, the check's edges as given. */
    private static DesignFlowDefinition flow(FlowGraphEdge... checkEdges) {
        Set<String> steps = new LinkedHashSet<>();
        for (FlowGraphEdge edge : checkEdges) {
            steps.add(edge.getTarget());
        }
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(
                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                approval("check"),
                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()));
        List<FlowGraphEdge> edges = new ArrayList<>(List.of(edge("start-check", "start", "check", null)));
        edges.addAll(List.of(checkEdges));
        for (String step : steps) {
            nodes.add(approval(step));
            edges.add(edge(step + "-end", step, "end", null));
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

    private static FlowGraphEdge edge(String id, String source, String target, String condition) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition).build();
    }
}
