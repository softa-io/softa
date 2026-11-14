package io.softa.starter.flow.runtime.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An inclusive gateway routes as BPMN's does: every conditional edge whose condition holds, and its default edge —
 * the one without a condition — only when none does.
 */
class FlowGatewayRouterInclusiveTest {

    private final FlowGatewayRouter router = new FlowGatewayRouter();

    private static final CompiledFlowTransition OVER_BUDGET = conditional("over-budget", "amount > 1000");
    private static final CompiledFlowTransition ABROAD = conditional("abroad", "abroad == true");
    private static final CompiledFlowTransition OTHERWISE = CompiledFlowTransition.builder()
            .edgeId("otherwise").source("gateway").target("otherwise").build();

    @Test
    void everyConditionThatHoldsIsTakenAndNotTheDefault() {
        CompiledFlowDefinition definition = gateway(OVER_BUDGET, ABROAD, OTHERWISE);

        assertEquals(List.of("over-budget", "abroad"), route(definition, 5000, true));
        assertEquals(List.of("abroad"), route(definition, 10, true));
    }

    @Test
    void theDefaultIsTakenOnlyWhenNoConditionHolds() {
        assertEquals(List.of("otherwise"), route(gateway(OVER_BUDGET, ABROAD, OTHERWISE), 10, false));
    }

    @Test
    void withoutADefaultNothingHoldingFailsTheInstance() {
        // Refused with what to do about it — a submission is told so, an instance past an approval fails with it.
        FlowActionValidationException failed = assertThrows(FlowActionValidationException.class,
                () -> route(gateway(OVER_BUDGET, ABROAD), 10, false));

        assertTrue(failed.getMessage().contains("has no default route"), failed.getMessage());
    }

    @Test
    void aGatewayWithTwoDefaultsFailsTheInstance() {
        CompiledFlowTransition alsoOtherwise = CompiledFlowTransition.builder()
                .edgeId("also").source("gateway").target("also").build();

        FlowRuntimeException failed = assertThrows(FlowRuntimeException.class,
                () -> route(gateway(OVER_BUDGET, OTHERWISE, alsoOtherwise), 5000, false));

        assertTrue(failed.getMessage().contains("multiple default outgoing edges"), failed.getMessage());
    }

    private List<String> route(CompiledFlowDefinition definition, int amount, boolean abroad) {
        return router.resolveNextNodeIds(definition, definition.getNodeIndex().get("gateway"),
                new FlowVariableContext(Map.of("amount", amount, "abroad", abroad), Map.of()));
    }

    private static CompiledFlowTransition conditional(String target, String expression) {
        return CompiledFlowTransition.builder()
                .edgeId(target).source("gateway").target(target).conditionExpression(expression).build();
    }

    private static CompiledFlowDefinition gateway(CompiledFlowTransition... edges) {
        Map<String, CompiledFlowTransition> transitions = new LinkedHashMap<>();
        for (CompiledFlowTransition edge : edges) {
            transitions.put(edge.getEdgeId(), edge);
        }
        CompiledFlowNode gateway = CompiledFlowNode.builder()
                .nodeId("gateway").type(FlowNodeType.INCLUSIVE_GATEWAY)
                .incomingEdgeIds(List.of()).outgoingEdgeIds(List.copyOf(transitions.keySet()))
                .build();
        return CompiledFlowDefinition.builder()
                .flowId("claim").nodeIndex(Map.of("gateway", gateway)).transitionIndex(transitions).build();
    }
}
