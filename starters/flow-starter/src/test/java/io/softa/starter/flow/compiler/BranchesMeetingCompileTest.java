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
 * Branches that may run at the same time meet only at a parallel join or an end, a join goes on by one edge, and a
 * fork's branch that goes on to a join enters it once on every path — otherwise a step runs once per branch, or a
 * join waits for ever and the approval finishes there.
 */
class BranchesMeetingCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void anExtraCheckThenHrCompiles() {
        // One conditional edge and the default never run together: HR is reached once either way.
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("gateway", FlowNodeType.INCLUSIVE_GATEWAY), approval("finance"), approval("hr")),
                List.of(edge("start-gateway", "start", "gateway"),
                        edge("gateway-finance", "gateway", "finance", "amount > 1000"),
                        edge("gateway-hr", "gateway", "hr"),
                        edge("finance-hr", "finance", "hr"),
                        edge("hr-end", "hr", "end"))));

        assertEquals(List.of(), diagnostics);
    }

    @Test
    void twoChecksMeetingAtHrAreRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("gateway", FlowNodeType.INCLUSIVE_GATEWAY),
                        approval("finance"), approval("travel"), approval("hr")),
                List.of(edge("start-gateway", "start", "gateway"),
                        edge("gateway-finance", "gateway", "finance", "amount > 1000"),
                        edge("gateway-travel", "gateway", "travel", "abroad == true"),
                        edge("gateway-hr", "gateway", "hr"),
                        edge("finance-hr", "finance", "hr"),
                        edge("travel-hr", "travel", "hr"),
                        edge("hr-end", "hr", "end"))));

        assertEquals(List.of("CONCURRENT_BRANCHES_MEET"), codes(diagnostics));
        CompileDiagnostic met = diagnostics.getFirst();
        assertEquals("gateway", met.nodeId());
        assertTrue(met.message().contains("'gateway → finance' and 'gateway → travel'"), met.message());
        assertTrue(met.message().contains("'hr'"), met.message());
    }

    @Test
    void forkBranchesMeetingAtAStepAreRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel"),
                        approval("hr")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-hr", "finance", "hr"),
                        edge("travel-hr", "travel", "hr"),
                        edge("hr-end", "hr", "end"))));

        assertEquals(List.of("CONCURRENT_BRANCHES_MEET"), codes(diagnostics));
        assertEquals("fork", diagnostics.getFirst().nodeId());
    }

    @Test
    void forkBranchesJoinedFirstOrEndingOnTheirOwnCompile() {
        List<CompileDiagnostic> joined = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel"),
                        node("join", FlowNodeType.PARALLEL_JOIN), approval("hr")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-join", "finance", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("join-hr", "join", "hr"),
                        edge("hr-end", "hr", "end"))));
        List<CompileDiagnostic> sharingAnEnd = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-end", "finance", "end"),
                        edge("travel-end", "travel", "end"))));

        assertEquals(List.of(), joined);
        assertEquals(List.of(), sharingAnEnd);
    }

    @Test
    void aForkNestedInABranchDoesNotHideAMeetingAfterItsJoin() {
        // outer: [inner fork: A, B → inner join] → hr, and [travel] → hr — hr runs once for each outer branch.
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("outer", FlowNodeType.PARALLEL_FORK), node("inner", FlowNodeType.PARALLEL_FORK),
                        approval("a"), approval("b"), node("innerJoin", FlowNodeType.PARALLEL_JOIN),
                        approval("travel"), approval("hr")),
                List.of(edge("start-outer", "start", "outer"),
                        edge("outer-inner", "outer", "inner"),
                        edge("outer-travel", "outer", "travel"),
                        edge("inner-a", "inner", "a"),
                        edge("inner-b", "inner", "b"),
                        edge("a-innerJoin", "a", "innerJoin"),
                        edge("b-innerJoin", "b", "innerJoin"),
                        edge("innerJoin-hr", "innerJoin", "hr"),
                        edge("travel-hr", "travel", "hr"),
                        edge("hr-end", "hr", "end"))));

        assertEquals(List.of("CONCURRENT_BRANCHES_MEET"), codes(diagnostics));
        assertEquals("outer", diagnostics.getFirst().nodeId());
        assertTrue(diagnostics.getFirst().message().contains("'hr'"), diagnostics.getFirst().message());
    }

    @Test
    void aNestedForkFeedingTheOuterJoinCompiles() {
        // Three arrivals for three incoming edges: the inner fork's two branches count as two.
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("outer", FlowNodeType.PARALLEL_FORK), node("inner", FlowNodeType.PARALLEL_FORK),
                        approval("a"), approval("b"), approval("travel"), node("join", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-outer", "start", "outer"),
                        edge("outer-inner", "outer", "inner"),
                        edge("outer-travel", "outer", "travel"),
                        edge("inner-a", "inner", "a"),
                        edge("inner-b", "inner", "b"),
                        edge("a-join", "a", "join"),
                        edge("b-join", "b", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("join-end", "join", "end"))));

        assertEquals(List.of(), diagnostics);
    }

    @Test
    void aJoinGoingOnByTwoEdgesIsRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel"),
                        node("join", FlowNodeType.PARALLEL_JOIN), approval("hr")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-join", "finance", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("join-hr", "join", "hr", "amount > 1000"),
                        edge("join-end", "join", "end"),
                        edge("hr-end", "hr", "end"))));

        assertEquals(List.of("JOIN_MULTIPLE_OUTGOING"), codes(diagnostics));
        assertEquals("join", diagnostics.getFirst().nodeId());
    }

    @Test
    void aBranchEnteringItsJoinByTwoEdgesIsRefused() {
        // Finance either goes to the join or asks a senior first: the join counts both edges, and gets one.
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("senior"),
                        approval("travel"), node("join", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-join", "finance", "join", "amount <= 1000"),
                        edge("finance-senior", "finance", "senior"),
                        edge("senior-join", "senior", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("join-end", "join", "end"))));

        assertEquals(List.of("BRANCH_ENTERS_JOIN_TWICE"), codes(diagnostics));
        CompileDiagnostic twice = diagnostics.getFirst();
        assertEquals("fork", twice.nodeId());
        assertTrue(twice.message().contains("'fork → finance'"), twice.message());
        assertTrue(twice.message().contains("'finance → join', 'senior → join'"), twice.message());
    }

    @Test
    void aBranchThatMayEndInsteadOfJoiningIsRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel"),
                        node("join", FlowNodeType.PARALLEL_JOIN), approval("hr")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-join", "finance", "join", "amount > 1000"),
                        edge("finance-end", "finance", "end"),
                        edge("travel-join", "travel", "join"),
                        edge("join-hr", "join", "hr"),
                        edge("hr-end", "hr", "end"))));

        assertEquals(List.of("BRANCH_SKIPS_JOIN"), codes(diagnostics));
        CompileDiagnostic skips = diagnostics.getFirst();
        assertEquals("fork", skips.nodeId());
        assertTrue(skips.message().contains("'fork → finance'"), skips.message());
        assertTrue(skips.message().contains("parallel join 'join' or an end"), skips.message());
    }

    private static List<String> codes(List<CompileDiagnostic> diagnostics) {
        return diagnostics.stream().map(CompileDiagnostic::code).toList();
    }

    /** START and END around {@code nodes}, wired by {@code edges}. */
    private static DesignFlowDefinition flow(List<FlowGraphNode> nodes, List<FlowGraphEdge> edges) {
        List<FlowGraphNode> all = new ArrayList<>(List.of(node("start", FlowNodeType.START),
                node("end", FlowNodeType.END)));
        all.addAll(nodes);
        return DesignFlowDefinition.builder()
                .name("Expense claim")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("ExpenseClaim"))
                .graph(FlowGraphDocument.builder().nodes(all).edges(edges).build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphNode approval(String id) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(id)
                .config(Map.of("approvers", List.of("2001"))).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return edge(id, source, target, null);
    }

    private static FlowGraphEdge edge(String id, String source, String target, String condition) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition).build();
    }
}
