package io.softa.starter.flow.runtime.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.context.RecordModelFixture;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A rule condition routes the way a field condition is judged — by the types of the record's fields,
 * a missing value ordering against nothing — where an AviatorScript expression would read nil as
 * smaller than any number.
 */
class FlowGatewayRouterRuleTest {

    private final FlowGatewayRouter router = new FlowGatewayRouter();
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = RecordModelFixture.mockModelManager();
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aRuleSendsTheRecordItMatchesItsWayAndTheRestDownTheDefaultEdge() {
        Filters shortLeave = Filters.of(List.of("days", "<", 3));

        assertEquals(List.of("ruled"), route(shortLeave, Map.of("days", new BigDecimal("2"))));
        assertEquals(List.of("fallback"), route(shortLeave, Map.of("days", new BigDecimal("5"))));
    }

    @Test
    void anEmptyFieldOrdersAgainstNothing() {
        // AviatorScript would take `days < 3` here: nil is smaller than any number.
        assertEquals(List.of("fallback"), route(Filters.of(List.of("days", "<", 3)), Map.of()));
    }

    @Test
    void datesCarriedAsIsoTextCompareAsDatesAndOptionsByItemCode() {
        Filters rule = Filters.of(List.of(
                List.of("startDate", ">=", "2026-11-01"), "AND", List.of("leaveType", "=", "Annual")));

        assertEquals(List.of("ruled"), route(rule, Map.of("startDate", "2026-11-03", "leaveType", "Annual")));
        assertEquals(List.of("fallback"), route(rule, Map.of("startDate", "2026-10-30", "leaveType", "Annual")));
    }

    @Test
    void anExpressionStillRoutesByAviatorScript() {
        CompiledFlowTransition ruled = CompiledFlowTransition.builder()
                .edgeId("ruled").source("start").target("ruled").conditionExpression("days > 3").build();

        assertEquals(List.of("ruled"), router.resolveNextNodeIds(definition(ruled), start(),
                new FlowVariableContext(Map.of("days", 5), Map.of())));
    }

    private List<String> route(Filters rule, Map<String, Object> variables) {
        CompiledFlowTransition ruled = CompiledFlowTransition.builder()
                .edgeId("ruled").source("start").target("ruled").condition(rule).build();
        return router.resolveNextNodeIds(definition(ruled), start(), new FlowVariableContext(variables, Map.of()));
    }

    private static CompiledFlowDefinition definition(CompiledFlowTransition ruled) {
        CompiledFlowTransition fallback = CompiledFlowTransition.builder()
                .edgeId("fallback").source("start").target("fallback").build();
        return CompiledFlowDefinition.builder()
                .modelName(MODEL).trigger(new SubmitTrigger())
                .nodeIndex(Map.of("start", start()))
                .transitionIndex(Map.of("ruled", ruled, "fallback", fallback))
                .build();
    }

    private static CompiledFlowNode start() {
        return CompiledFlowNode.builder()
                .nodeId("start")
                .type(FlowNodeType.START)
                .outgoingEdgeIds(List.of("ruled", "fallback"))
                .build();
    }
}
