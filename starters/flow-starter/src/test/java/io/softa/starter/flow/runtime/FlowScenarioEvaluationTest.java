package io.softa.starter.flow.runtime;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowOnchangeRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowValidationRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.ReturnValueNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.ScriptNodeExecutionHandler;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario-driven execution strategy: Validation / Compute flows evaluate transiently —
 * no instance is persisted, success or fail — while Approval and Automation flows persist via start().
 */
class FlowScenarioEvaluationTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore();
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();

    private final FlowRuntimeEngine runtimeEngine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            instanceStore,
            new DefaultNodeHandlerRegistry(List.of(
                    approvalHandler,
                    new ScriptNodeExecutionHandler(),
                    new ReturnValueNodeExecutionHandler(),
                    new PassThroughNodeExecutionHandler()
            )),
            approvalHandler);

    private final FlowAutomationService automationService = new FlowAutomationService(
            new DefaultFlowTriggerRegistry(bundleRegistry), runtimeEngine);

    @Test
    void computeFlowEvaluatesTransientlyThroughOnchange() {
        publishService.publish("9101", computeFlow());

        FlowOnchangeRequest request = new FlowOnchangeRequest();
        request.setSourceModel("Order");
        request.setFieldChanges(Map.of("price", 5, "quantity", 4));
        request.setCurrentData(Map.of());

        Map<String, Object> diff = automationService.evaluateOnchange(request);

        assertEquals(20L, ((Number) diff.get("totalAmount")).longValue());
        // Transient evaluation: no instance row exists after the onchange round-trip.
        assertTrue(instanceStore.listByFlowId("9101").isEmpty());
    }

    @Test
    void validationFlowEvaluatesTransientlyAndReturnsDeclaredOutputs() {
        publishService.publish("9102", validationFlow());

        FlowValidationRequest request = new FlowValidationRequest();
        request.setSourceModel("Order");
        request.setData(Map.of("amount", -1));

        Map<String, Map<String, Object>> outputs = automationService.evaluateValidation(request);

        assertEquals(Boolean.FALSE, outputs.get("9102").get("valid"));
        assertTrue(instanceStore.listByFlowId("9102").isEmpty());
    }

    @Test
    void evaluateRejectsAutomationBundles() {
        publishService.publish("9103", automationFlow());

        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId("9103");
        request.setInitiatorId("user-1");

        assertThrows(FlowActionValidationException.class, () -> runtimeEngine.evaluate(request));
    }

    private static DesignFlowDefinition computeFlow() {
        return DesignFlowDefinition.builder()
                .name("Compute Total")
                .scenario(FlowScenario.COMPUTE)
                .modelName("Order").trigger(new FieldChangeTrigger(List.of()))
                .declaredOutputs(List.of("totalAmount"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                scriptNode("calc", "price * quantity", "totalAmount"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "calc"),
                                edge("e2", "calc", "end")
                        ))
                        .build())
                .build();
    }

    private static DesignFlowDefinition validationFlow() {
        return DesignFlowDefinition.builder()
                .name("Validate Amount")
                .scenario(FlowScenario.VALIDATION)
                .modelName("Order").trigger(new EntityChangeTrigger(List.of(), null))
                .declaredOutputs(List.of("valid"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                scriptNode("check", "amount > 0", "valid"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "check"),
                                edge("e2", "check", "end")
                        ))
                        .build())
                .build();
    }

    private static DesignFlowDefinition automationFlow() {
        return DesignFlowDefinition.builder()
                .name("Plain Automation")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(edge("e1", "start", "end")))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphNode scriptNode(String id, String expression, String outputVariable) {
        return FlowGraphNode.builder()
                .id(id)
                .type(FlowNodeType.SCRIPT)
                .label(id)
                .config(Map.of("expression", expression, "outputVariable", outputVariable))
                .build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
