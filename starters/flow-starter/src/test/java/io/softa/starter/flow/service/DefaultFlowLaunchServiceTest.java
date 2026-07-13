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
import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.api.PublishAndStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.*;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.task.builtin.DefaultTaskExecutorRegistry;
import io.softa.starter.flow.runtime.task.EchoServiceTaskExecutor;
import io.softa.starter.flow.runtime.task.RecordLookupDataTaskExecutor;
import io.softa.starter.flow.runtime.task.TemplateMessageTaskExecutor;
import io.softa.starter.flow.service.impl.FlowLaunchServiceImpl;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultFlowLaunchServiceTest {

    private static final Long DESIGN_ID = 10L;

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final DefaultTaskExecutorRegistry taskExecutorRegistry = new DefaultTaskExecutorRegistry(List.of(
            new EchoServiceTaskExecutor(),
            new RecordLookupDataTaskExecutor(),
            new TemplateMessageTaskExecutor()
    ));
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine runtimeEngine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(
                    approvalHandler,
                    new TaskNodeExecutionHandler(taskExecutorRegistry),
                    new SubflowNodeExecutionHandler(),
                    new ScriptNodeExecutionHandler(),
                    new ForEachNodeExecutionHandler(),
                    new ReturnValueNodeExecutionHandler(),
                    new PassThroughNodeExecutionHandler()
            )),
            approvalHandler
    );
    private final FlowLaunchServiceImpl launchService =
            new FlowLaunchServiceImpl(publishService, runtimeEngine, bundleRegistry, new DefaultFlowCompiler());

    @Test
    void shouldPublishAndStartExactRevision() {
        PublishAndStartRequest request = new PublishAndStartRequest();
        request.setDesignId(DESIGN_ID);
        request.setDefinition(flowDefinition("launchable-flow", "v1"));
        request.setInitiatorId("initiator-100");
        request.setVariables(Map.of("requestId", 100L));

        FlowLaunchResponse response = launchService.publishAndStart(request);

        assertEquals(1, response.getBundle().getRevision());
        assertEquals(1, response.getState().getFlowRevision());
        assertEquals("initiator-100", response.getState().getInitiatorId());
        assertEquals(FlowExecutionStatus.COMPLETED, response.getState().getStatus());
        assertEquals("v1", ((Map<?, ?>) response.getState().getVariables().get("serviceResult")).get("route"));
    }

    private static DesignFlowDefinition flowDefinition(String code, String routeValue) {
        return DesignFlowDefinition.builder()
                .code(code)
                .name(routeValue)
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                taskNode("service", FlowNodeType.CALL_SERVICE,
                                        Map.of("beanName", "echoService", "methodName", "run", "route", routeValue),
                                        "serviceResult"),
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
