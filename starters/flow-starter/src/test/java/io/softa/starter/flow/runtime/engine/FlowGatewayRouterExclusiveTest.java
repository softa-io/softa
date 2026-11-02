package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A node that is not a gateway takes the first of its conditional edges, in order, whose condition holds — two holding
 * no longer fail the instance — and its default only when none does. A revision compiled before priorities lists its
 * edges in drawing order, its default anywhere among them.
 */
class FlowGatewayRouterExclusiveTest {

    private final FlowGatewayRouter router = new FlowGatewayRouter();

    private static final CompiledFlowTransition CEO = conditional("ceo", "amount > 10000");
    private static final CompiledFlowTransition FINANCE = conditional("finance", "amount > 100");
    private static final CompiledFlowTransition HR = CompiledFlowTransition.builder()
            .edgeId("hr").source("check").target("hr").build();

    @Test
    void theFirstConditionThatHoldsIsTakenAndTheRestAreNotRead() {
        List<String> read = new ArrayList<>();
        CompiledFlowDefinition definition = node(CEO, FINANCE, HR);

        List<String> next = router.resolveNextNodeIds(definition, definition.getNodeIndex().get("check"), edge -> {
            read.add(edge.getEdgeId());
            return true;
        });

        assertEquals(List.of("ceo"), next, "both hold: the first is taken, nothing fails");
        assertEquals(List.of("ceo"), read);
        assertEquals(List.of("finance"), route(definition, 5000));
        assertEquals(List.of("hr"), route(definition, 10));
    }

    @Test
    void aDefaultListedAmongTheConditionalEdgesIsStillTakenOnlyWhenNoneHolds() {
        CompiledFlowDefinition drawnBeforePriorities = node(HR, FINANCE, CEO);

        assertEquals(List.of("finance"), route(drawnBeforePriorities, 50000), "drawing order: finance comes first");
        assertEquals(List.of("hr"), route(drawnBeforePriorities, 10));
    }

    @Test
    void withoutADefaultNothingHoldingStillFailsTheInstance() {
        assertThrows(FlowRuntimeException.class, () -> route(node(CEO, FINANCE), 10));
    }

    private List<String> route(CompiledFlowDefinition definition, int amount) {
        return router.resolveNextNodeIds(definition, definition.getNodeIndex().get("check"),
                new FlowVariableContext(Map.of("amount", amount), Map.of()));
    }

    private static CompiledFlowTransition conditional(String target, String expression) {
        return CompiledFlowTransition.builder()
                .edgeId(target).source("check").target(target).conditionExpression(expression).build();
    }

    private static CompiledFlowDefinition node(CompiledFlowTransition... edges) {
        Map<String, CompiledFlowTransition> transitions = new LinkedHashMap<>();
        for (CompiledFlowTransition edge : edges) {
            transitions.put(edge.getEdgeId(), edge);
        }
        CompiledFlowNode check = CompiledFlowNode.builder()
                .nodeId("check").type(FlowNodeType.APPROVAL)
                .incomingEdgeIds(List.of()).outgoingEdgeIds(List.copyOf(transitions.keySet()))
                .build();
        return CompiledFlowDefinition.builder()
                .flowId("claim").nodeIndex(Map.of("check", check)).transitionIndex(transitions).build();
    }
}
