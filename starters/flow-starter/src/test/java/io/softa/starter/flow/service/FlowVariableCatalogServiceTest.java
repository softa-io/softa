package io.softa.starter.flow.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.meta.ModelManager;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowVariableView;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.context.RecordModelFixture;

import static org.junit.jupiter.api.Assertions.*;

class FlowVariableCatalogServiceTest {

    private final FlowVariableCatalogService catalog = new FlowVariableCatalogService();

    /**
     * Graph: start → script(sum) → task(order) → target; a parallel dead branch
     * declares an output that must NOT be visible to target.
     */
    @Test
    void availableVariables_collectsOnlyUpstreamOutputs() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Var Flow")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START, null),
                                node("script1", FlowNodeType.SCRIPT,
                                        Map.of("expression", "1+1", "outputVariable", "sum")),
                                node("task1", FlowNodeType.GET_RECORD,
                                        Map.of("task", Map.of("outputVariable", "order"))),
                                node("target", FlowNodeType.CALL_WEBHOOK, Map.of()),
                                node("elsewhere", FlowNodeType.SCRIPT,
                                        Map.of("expression", "2", "outputVariable", "unreachable"))))
                        .edges(List.of(
                                edge("e1", "start", "script1"),
                                edge("e2", "script1", "task1"),
                                edge("e3", "task1", "target"),
                                edge("e4", "start", "elsewhere")))
                        .build())
                .build();

        List<FlowVariableView> variables = catalog.availableVariables(definition, "target");
        List<String> names = variables.stream().map(FlowVariableView::name).toList();

        assertTrue(names.contains("sum"));
        assertTrue(names.contains("order"));
        assertFalse(names.contains("unreachable"));
        assertTrue(names.contains("_triggerType"));

        FlowVariableView sum = variables.stream().filter(v -> "sum".equals(v.name())).findFirst().orElseThrow();
        assertEquals(FlowVariableView.Source.NODE_OUTPUT, sum.source());
        assertEquals("script1", sum.sourceNodeId());
    }

    @Test
    void availableVariables_withoutNodeIdReturnsTriggerAndBuiltinsOnly() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Var Flow")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("script1", FlowNodeType.SCRIPT,
                                Map.of("outputVariable", "sum"))))
                        .edges(List.of())
                        .build())
                .build();

        List<String> names = catalog.availableVariables(definition, null).stream()
                .map(FlowVariableView::name)
                .toList();

        assertFalse(names.contains("sum"));
        assertTrue(names.contains("_triggerType"));
    }

    @Test
    void anApprovalOffersTheFieldsItsApprovalsRouteOnAsTriggerVariables() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Leave Approval")
                .scenario(FlowScenario.APPROVAL)
                .modelName(RecordModelFixture.MODEL).trigger(new SubmitTrigger())
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("start", FlowNodeType.START, null)))
                        .edges(List.of())
                        .build())
                .build();

        try (MockedStatic<ModelManager> ignored = RecordModelFixture.mockModelManager()) {
            List<FlowVariableView> trigger = catalog.availableVariables(definition, null).stream()
                    .filter(variable -> variable.source() == FlowVariableView.Source.TRIGGER)
                    .toList();

            assertEquals(RecordModelFixture.ROUTED, trigger.stream().map(FlowVariableView::name).toList());
            FlowVariableView days = trigger.getFirst();
            assertEquals("BigDecimal", days.type());
            assertEquals(RecordModelFixture.MODEL, days.sourceLabel());
        }
    }

    @Test
    void theEdgesLeavingANodeAlsoSeeItsOwnOutputs() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Var Flow")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START, null),
                                node("script1", FlowNodeType.SCRIPT,
                                        Map.of("expression", "1+1", "outputVariable", "sum")),
                                node("script2", FlowNodeType.SCRIPT,
                                        Map.of("expression", "sum * 2", "outputVariable", "doubled"))))
                        .edges(List.of(edge("e1", "start", "script1"), edge("e2", "script1", "script2")))
                        .build())
                .build();

        List<String> atNode = catalog.availableVariables(definition, "script2").stream()
                .map(FlowVariableView::name).toList();
        List<String> afterNode = catalog.availableVariables(definition, "script2", true).stream()
                .map(FlowVariableView::name).toList();

        assertTrue(atNode.contains("sum"));
        assertFalse(atNode.contains("doubled"));
        assertTrue(afterNode.containsAll(List.of("sum", "doubled")));
    }

    private static FlowGraphNode node(String id, FlowNodeType type, Map<String, Object> config) {
        return FlowGraphNode.builder().id(id).type(type).label(id).config(config).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
