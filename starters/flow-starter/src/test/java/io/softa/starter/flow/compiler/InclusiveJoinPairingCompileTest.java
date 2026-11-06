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
 * An inclusive gateway whose branches meet is paired with one join: every branch goes on to it and enters it by one
 * edge, shares no node with another, and nothing from outside comes in. Gateways and forks nest, each with its own
 * join.
 */
class InclusiveJoinPairingCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void anEdgeFromOutsideIntoTheJoinIsRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(approval("check"), node("gateway", FlowNodeType.INCLUSIVE_GATEWAY), approval("finance"),
                        approval("travel"), approval("other"), node("join", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-check", "start", "check"),
                        edge("check-gateway", "check", "gateway", "amount > 0"),
                        edge("check-other", "check", "other"),
                        edge("g-finance", "gateway", "finance", "amount > 1000"),
                        edge("g-travel", "gateway", "travel", "abroad == true"),
                        edge("finance-join", "finance", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("other-join", "other", "join"),
                        edge("join-end", "join", "end"))));

        assertEquals(List.of("INCLUSIVE_JOIN_UNPAIRED"), codes(diagnostics));
        assertTrue(diagnostics.getFirst().message().contains("entered from outside the gateway's branches, by 'other → join'"),
                diagnostics.getFirst().message());
    }

    @Test
    void aBranchEnteringTheJoinByTwoEdgesIsRefused() {
        // Finance either goes straight on or asks a senior first: two ways into the join, one of which is taken.
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("gateway", FlowNodeType.INCLUSIVE_GATEWAY), approval("finance"), approval("senior"),
                        approval("travel"), node("join", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-gateway", "start", "gateway"),
                        edge("g-finance", "gateway", "finance", "amount > 1000"),
                        edge("g-travel", "gateway", "travel", "abroad == true"),
                        edge("finance-join", "finance", "join", "amount <= 5000"),
                        edge("finance-senior", "finance", "senior"),
                        edge("senior-join", "senior", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("join-end", "join", "end"))));

        assertEquals(List.of("INCLUSIVE_JOIN_UNPAIRED"), codes(diagnostics));
        assertTrue(diagnostics.getFirst().message().contains("enters join 'join' by 2 edges"),
                diagnostics.getFirst().message());
    }

    @Test
    void branchesSharingAStepBeforeTheJoinAreRefused() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("gateway", FlowNodeType.INCLUSIVE_GATEWAY), approval("finance"), approval("travel"),
                        approval("hr"), node("join", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-gateway", "start", "gateway"),
                        edge("g-finance", "gateway", "finance", "amount > 1000"),
                        edge("g-travel", "gateway", "travel", "abroad == true"),
                        edge("g-join", "gateway", "join"),
                        edge("finance-hr", "finance", "hr"),
                        edge("travel-hr", "travel", "hr"),
                        edge("hr-join", "hr", "join"),
                        edge("join-end", "join", "end"))));

        assertEquals(List.of("INCLUSIVE_JOIN_UNPAIRED"), codes(diagnostics));
        assertTrue(diagnostics.getFirst().message().contains("meet at 'hr' before join 'join'"),
                diagnostics.getFirst().message());
    }

    @Test
    void gatewaysAndForksNestEachWithItsOwnJoin() {
        // outer gateway: [inner gateway: c, d → inner join] → outer join, [travel] → outer join, default → outer join;
        // and a fork whose first branch is a gateway closed by its own join.
        DesignFlowDefinition design = flow(
                List.of(node("outer", FlowNodeType.INCLUSIVE_GATEWAY), node("inner", FlowNodeType.INCLUSIVE_GATEWAY),
                        approval("c"), approval("d"), node("innerJoin", FlowNodeType.PARALLEL_JOIN), approval("travel"),
                        node("outerJoin", FlowNodeType.PARALLEL_JOIN), node("fork", FlowNodeType.PARALLEL_FORK),
                        node("forkGateway", FlowNodeType.INCLUSIVE_GATEWAY), approval("e"), approval("f"),
                        node("forkGatewayJoin", FlowNodeType.PARALLEL_JOIN), approval("g"),
                        node("forkJoin", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-outer", "start", "outer"),
                        edge("outer-inner", "outer", "inner", "amount > 1000"),
                        edge("outer-travel", "outer", "travel", "abroad == true"),
                        edge("outer-join", "outer", "outerJoin"),
                        edge("inner-c", "inner", "c", "amount > 5000"),
                        edge("inner-d", "inner", "d", "amount > 2000"),
                        edge("c-innerJoin", "c", "innerJoin"),
                        edge("d-innerJoin", "d", "innerJoin"),
                        edge("innerJoin-outerJoin", "innerJoin", "outerJoin"),
                        edge("travel-outerJoin", "travel", "outerJoin"),
                        edge("outerJoin-fork", "outerJoin", "fork"),
                        edge("fork-forkGateway", "fork", "forkGateway"),
                        edge("fork-g", "fork", "g"),
                        edge("forkGateway-e", "forkGateway", "e", "amount > 1000"),
                        edge("forkGateway-f", "forkGateway", "f", "abroad == true"),
                        edge("e-forkGatewayJoin", "e", "forkGatewayJoin"),
                        edge("f-forkGatewayJoin", "f", "forkGatewayJoin"),
                        edge("forkGatewayJoin-forkJoin", "forkGatewayJoin", "forkJoin"),
                        edge("g-forkJoin", "g", "forkJoin"),
                        edge("forkJoin-end", "forkJoin", "end")));

        assertEquals(List.of(), compiler.validate(design));
        CompiledFlowDefinition compiled = compiler.compile(design);
        assertEquals("outerJoin", compiled.getNodeIndex().get("outer").getPairedJoinId());
        assertEquals("innerJoin", compiled.getNodeIndex().get("inner").getPairedJoinId());
        assertEquals("forkGatewayJoin", compiled.getNodeIndex().get("forkGateway").getPairedJoinId());
    }

    @Test
    void aGatewaysBranchesMeetingAtTheJoinOfAForkAroundItAreRefused() {
        // The fork's join counts the gateway's branches as the fork's: it would wait for one the gateway skipped.
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), node("gateway", FlowNodeType.INCLUSIVE_GATEWAY),
                        approval("e"), approval("f"), approval("g"), node("forkJoin", FlowNodeType.PARALLEL_JOIN)),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-gateway", "fork", "gateway"),
                        edge("fork-g", "fork", "g"),
                        edge("gateway-e", "gateway", "e", "amount > 1000"),
                        edge("gateway-f", "gateway", "f", "abroad == true"),
                        edge("e-forkJoin", "e", "forkJoin"),
                        edge("f-forkJoin", "f", "forkJoin"),
                        edge("g-forkJoin", "g", "forkJoin"),
                        edge("forkJoin-end", "forkJoin", "end"))));

        assertTrue(codes(diagnostics).contains("INCLUSIVE_JOIN_UNPAIRED"), codes(diagnostics).toString());
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
                .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of("2001"))))).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return edge(id, source, target, null);
    }

    private static FlowGraphEdge edge(String id, String source, String target, String condition) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition).build();
    }
}
