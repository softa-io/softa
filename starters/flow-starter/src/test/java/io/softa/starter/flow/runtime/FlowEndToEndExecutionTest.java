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
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.WaitType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;
import io.softa.starter.flow.runtime.task.builtin.DefaultTaskExecutorRegistry;
import io.softa.starter.flow.runtime.task.EchoServiceTaskExecutor;
import io.softa.starter.flow.runtime.task.RecordLookupDataTaskExecutor;
import io.softa.starter.flow.runtime.task.TemplateMessageTaskExecutor;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of a simple flow: START → Approval (single approver) → CALL_SERVICE → END.
 * Validates the full engine wiring without Spring context:
 * suspend on approval, resume on approve, task execution with echo stub, final COMPLETED status.
 */
class FlowEndToEndExecutionTest {

    private static final String FLOW_ID = "7001";

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
                    new ReturnValueNodeExecutionHandler(),
                    new TimerNodeExecutionHandler(),
                    new PassThroughNodeExecutionHandler()
            )),
            approvalHandler
    );

    @Test
    void shouldSuspendOnATimerAndResumeThroughCallServiceToEnd() {
        publishService.publish(FLOW_ID, flowDefinition());

        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setFlowId(FLOW_ID);
        startRequest.setInitiatorId("initiator-1");
        startRequest.setVariables(Map.of("requestId", 42L));

        FlowExecutionState afterStart = runtimeEngine.start(startRequest);

        assertEquals(FlowExecutionStatus.WAITING, afterStart.getStatus(), "Flow should suspend on the timer");
        assertTrue(afterStart.getWaitTokens().stream().anyMatch(t -> t.getType() == WaitType.TIMER),
                "The timer parks the instance as a timer wait token");

        FlowExecutionState afterResume = runtimeEngine.resumeTimer(afterStart.getInstanceId(), "wait");

        assertEquals(FlowExecutionStatus.COMPLETED, afterResume.getStatus(),
                "Flow should run through the service task and complete");
        Object serviceResult = afterResume.getVariables().get("serviceResult");
        assertTrue(serviceResult instanceof Map<?, ?>, "Service task must write its result under the output variable");
        assertEquals("e2e", ((Map<?, ?>) serviceResult).get("route"),
                "Echo executor must reflect the configured input back into variables");
        assertNotNull(afterResume.getTrace(), "Execution trace must be present");
        assertFalse(afterResume.getTrace().isEmpty(), "Trace must record node executions");
    }

    @Test
    void returnValueNodePopulatesSynchronousReturnData() {
        String flowId = "7003";
        publishService.publish(flowId, returnValueFlow());

        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setFlowId(flowId);
        startRequest.setInitiatorId("initiator-1");
        startRequest.setVariables(Map.of("amount", 500L));

        FlowExecutionState state = runtimeEngine.start(startRequest);

        // Regression: the ReturnValue node's outputs must reach state.returnData (the _returnData
        // envelope read by onchange / trigger callers), not only the variables tier.
        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        assertInstanceOf(Map.class, state.getReturnData(), "ReturnValue node must populate returnData");
        assertEquals(500L, ((Map<?, ?>) state.getReturnData()).get("approvedAmount"));
    }

    private static DesignFlowDefinition returnValueFlow() {
        return DesignFlowDefinition.builder()
                .name("E2E Return Value")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                returnValueNode("ret", Map.of("approvedAmount", "amount")),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "ret"),
                                edge("e2", "ret", "end")
                        ))
                        .build())
                .build();
    }

    private static FlowGraphNode returnValueNode(String id, Map<String, Object> outputExpressions) {
        return FlowGraphNode.builder()
                .id(id)
                .type(FlowNodeType.RETURN_VALUE)
                .label(id)
                .config(Map.of("outputExpressions", outputExpressions))
                .build();
    }

    private static FlowGraphNode timerNode(String id, long durationSeconds) {
        return FlowGraphNode.builder()
                .id(id)
                .type(FlowNodeType.TIMER)
                .label(id)
                .config(Map.of("durationSeconds", durationSeconds))
                .build();
    }

    @Test
    void failedStartRecordsFailedInstanceAndRethrows() {
        String flowId = "7004";
        StubFlowBundleRegistry localBundles = new StubFlowBundleRegistry();
        StubFlowInstanceStore localStore = new StubFlowInstanceStore();
        ApprovalNodeExecutionHandler approval = new ApprovalNodeExecutionHandler();
        FlowRuntimeEngine localEngine = TestFlowRuntimeFactory.create(
                localBundles,
                localStore,
                new DefaultNodeHandlerRegistry(List.of(
                        new TaskNodeExecutionHandler(
                                new DefaultTaskExecutorRegistry(List.of(new ThrowingWebhookExecutor()))),
                        approval,
                        new PassThroughNodeExecutionHandler())),
                approval);
        new FlowPublishServiceImpl(compiler, localBundles).publish(flowId, failingFlow());

        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId(flowId);
        request.setInitiatorId("initiator-1");

        // The failing node propagates, so start() rethrows — callers (e.g. trigger dispatch) rely on
        // this to record the failure. But the FAILED instance is still persisted (in production via
        // its own transaction, so it survives the start rollback).
        assertThrows(RuntimeException.class, () -> localEngine.start(request));

        List<FlowExecutionState> failed = localStore.listByStatus(FlowExecutionStatus.FAILED);
        assertEquals(1, failed.size(), "a FAILED instance must be recorded even when start fails");
        assertEquals(flowId, failed.get(0).getFlowId());
    }

    /** Test stub: a CALL_WEBHOOK executor that always throws, to force a node failure during start. */
    private static final class ThrowingWebhookExecutor implements TaskExecutor {
        @Override
        public FlowNodeType getSupportedNodeType() {
            return FlowNodeType.CALL_WEBHOOK;
        }

        @Override
        public String getExecutor() {
            return "throwing";
        }

        @Override
        public String getName() {
            return "throwing";
        }

        @Override
        public String getDescription() {
            return "always throws";
        }

        @Override
        public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
            throw new FlowRuntimeException("boom");
        }
    }

    private static DesignFlowDefinition failingFlow() {
        return DesignFlowDefinition.builder()
                .name("E2E Failing")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                taskNode("hook", FlowNodeType.CALL_WEBHOOK,
                                        Map.of("url", "https://example.invalid/hook"), "hookResult"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "hook"),
                                edge("e2", "hook", "end")
                        ))
                        .build())
                .build();
    }

    private static DesignFlowDefinition flowDefinition() {
        return DesignFlowDefinition.builder()
                .name("E2E Wait Then Service")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                timerNode("wait", 3600L),
                                taskNode("service", FlowNodeType.CALL_SERVICE,
                                        Map.of("beanName", "echoService", "methodName", "run", "route", "e2e"),
                                        "serviceResult"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "wait"),
                                edge("e2", "wait", "service"),
                                edge("e3", "service", "end")
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
