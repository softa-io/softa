package io.softa.starter.flow.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.StubFlowInstanceStore;
import io.softa.starter.flow.runtime.TestFlowRuntimeFactory;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.task.builtin.DefaultTaskExecutorRegistry;
import io.softa.starter.flow.runtime.task.EchoWebhookTaskExecutor;
import io.softa.starter.flow.runtime.task.RecordLookupDataTaskExecutor;
import io.softa.starter.flow.runtime.task.TemplateMessageTaskExecutor;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFlowPublishServiceTest {

    private static final String FLOW_ID = "42";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);

    @Test
    void shouldPublishRevisionsAndExposeLatestBundle() {
        CompiledFlowDefinition v1 = publishService.publish(FLOW_ID, flowDefinition("publishable-flow", Map.of("route", "v1")));
        CompiledFlowDefinition v2 = publishService.publish(FLOW_ID, flowDefinition("publishable-flow", Map.of("route", "v2")));

        assertEquals(1, v1.getRevision());
        assertEquals(2, v2.getRevision());
        // Revision reads live on the bundle registry (REST: /flow/bundles).
        assertEquals("v2", bundleRegistry.getActiveByFlowId(FLOW_ID).orElseThrow().getFlowName());
        assertEquals(1, bundleRegistry.getByBundleId(v1.getBundleId()).orElseThrow().getRevision());
        assertNotNull(v2.getPublishedAt());
    }

    @Test
    void shouldStartLatestPublishedBundleByFlowId() {
        publishService.publish(FLOW_ID, flowDefinition("latest-flow", Map.of("route", "old")));
        publishService.publish(FLOW_ID, flowDefinition("latest-flow", Map.of("route", "latest")));

        DefaultTaskExecutorRegistry taskExecutorRegistry = new DefaultTaskExecutorRegistry(List.of(
                new EchoWebhookTaskExecutor(),
                new RecordLookupDataTaskExecutor(),
                new TemplateMessageTaskExecutor()
        ));
        ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
        FlowRuntimeEngine runtimeEngine = TestFlowRuntimeFactory.create(
                bundleRegistry,
                new StubFlowInstanceStore(),
                new DefaultNodeHandlerRegistry(List.of(
                        approvalHandler,
                        new TaskNodeExecutionHandler(taskExecutorRegistry),
                        new SubflowNodeExecutionHandler(),
                        new ScriptNodeExecutionHandler(),
                        new ReturnValueNodeExecutionHandler(),
                        new PassThroughNodeExecutionHandler()
                )),
                approvalHandler
        );

        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId(FLOW_ID);
        FlowExecutionState state = runtimeEngine.start(request);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        assertTrue(state.getVariables().get("routeResult") instanceof Map<?, ?>);
        assertEquals("latest", ((Map<?, ?>) state.getVariables().get("routeResult")).get("route"));
    }

    private static DesignFlowDefinition flowDefinition(String code, Map<String, Object> taskInput) {
        String flowName = taskInput.get("route").toString();
        // CALL_WEBHOOK requires a url at compile; the echo stub calls nothing.
        Map<String, Object> serviceInput = Map.of("url", "https://example.com/hook", "route", taskInput.get("route"));
        return DesignFlowDefinition.builder()
                .name(flowName)
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                taskNode("service", FlowNodeType.CALL_WEBHOOK, serviceInput, "routeResult"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "service"),
                                edge("e2", "service", "end")
                        ))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphNode taskNode(String id, FlowNodeType type,
                                          Map<String, Object> input,
                                          String outputVariable) {
        return FlowGraphNode.builder()
                .id(id)
                .type(type)
                .label(id)
                .config(Map.of(
                        "input", input,
                        "outputVariable", outputVariable
                ))
                .build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
