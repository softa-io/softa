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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An inclusive gateway has at most one default edge, and none of its branches reaches a parallel join — which would
 * wait for a branch the gateway did not take and let the approval finish there.
 */
class InclusiveGatewayCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void branchesEndingOnTheirOwnWithOneDefaultCompile() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(List.of(
                edge("g-finance", "gateway", "finance", "amount > 1000"),
                edge("g-travel", "gateway", "travel", "abroad == true"),
                edge("g-end", "gateway", "end", null),
                edge("finance-end", "finance", "end", null),
                edge("travel-end", "travel", "end", null)), false));

        assertEquals(List.of(), diagnostics);
    }

    @Test
    void twoEdgesWithoutAConditionAreRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(List.of(
                edge("g-finance", "gateway", "finance", null),
                edge("g-travel", "gateway", "travel", null),
                edge("finance-end", "finance", "end", null),
                edge("travel-end", "travel", "end", null)), false));

        assertEquals(List.of("MULTIPLE_DEFAULT_EDGES"), codes(diagnostics));
        assertEquals("gateway", diagnostics.getFirst().nodeId());
    }

    @Test
    void aBranchReachingAParallelJoinIsRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(List.of(
                edge("g-finance", "gateway", "finance", "amount > 1000"),
                edge("g-travel", "gateway", "travel", "abroad == true"),
                edge("finance-join", "finance", "join", null),
                edge("travel-join", "travel", "join", null),
                edge("join-end", "join", "end", null)), true));

        assertEquals(List.of("INCLUSIVE_BRANCH_JOINED"), codes(diagnostics));
        CompileDiagnostic joined = diagnostics.getFirst();
        assertEquals(CompileDiagnostic.Severity.ERROR, joined.severity());
        assertEquals("gateway", joined.nodeId());
        assertTrue(joined.message().contains("parallel join 'join'"), joined.message());
    }

    private static List<String> codes(List<CompileDiagnostic> diagnostics) {
        return diagnostics.stream().map(CompileDiagnostic::code).toList();
    }

    /** START → inclusive gateway → Finance and Travel approvals (→ a parallel join) → END, wired by {@code edges}. */
    private static DesignFlowDefinition flow(List<FlowGraphEdge> edges, boolean join) {
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(
                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                FlowGraphNode.builder().id("gateway").type(FlowNodeType.INCLUSIVE_GATEWAY).label("Which checks").build(),
                approval("finance", "Finance"),
                approval("travel", "Travel"),
                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()));
        if (join) {
            nodes.add(FlowGraphNode.builder().id("join").type(FlowNodeType.PARALLEL_JOIN).label("Join").build());
        }
        List<FlowGraphEdge> all = new ArrayList<>(List.of(edge("start-g", "start", "gateway", null)));
        all.addAll(edges);
        return DesignFlowDefinition.builder()
                .name("Expense claim")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("ExpenseClaim"))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(all).build())
                .build();
    }

    private static FlowGraphNode approval(String id, String label) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(label)
                .config(Map.of("approvers", List.of("2001"))).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target, String condition) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition).build();
    }
}
