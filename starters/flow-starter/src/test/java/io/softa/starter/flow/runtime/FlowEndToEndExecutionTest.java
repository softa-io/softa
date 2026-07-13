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
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
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

    private static final Long DESIGN_ID = 7001L;
    private static final String APPROVER = "manager-1";

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
                    new TimerNodeExecutionHandler(),
                    new PassThroughNodeExecutionHandler()
            )),
            approvalHandler
    );

    @Test
    void shouldSuspendOnApprovalAndResumeThroughCallServiceToEnd() {
        publishService.publish(DESIGN_ID, flowDefinition());

        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setDesignId(DESIGN_ID);
        startRequest.setInitiatorId("initiator-1");
        startRequest.setVariables(Map.of("requestId", 42L));

        FlowExecutionState afterStart = runtimeEngine.start(startRequest);

        assertEquals(FlowExecutionStatus.WAITING, afterStart.getStatus(),
                "Flow should suspend on the approval node");
        assertNotNull(afterStart.getPendingApprovals(), "Pending approvals should be populated");
        assertFalse(afterStart.getPendingApprovals().isEmpty(), "At least one approval must be pending");
        PendingApproval pending = afterStart.getPendingApprovals().get(0);
        assertEquals("approval", pending.getNodeId());
        assertTrue(pending.getApprovers().contains(APPROVER),
                "Configured approver must appear in the pending approval");

        FlowApproveRequest approveRequest = new FlowApproveRequest();
        approveRequest.setInstanceId(afterStart.getInstanceId());
        approveRequest.setNodeId(pending.getNodeId());
        approveRequest.setActorId(APPROVER);
        approveRequest.setComment("looks good");

        FlowExecutionState afterApprove = runtimeEngine.approve(approveRequest);

        assertEquals(FlowExecutionStatus.COMPLETED, afterApprove.getStatus(),
                "Flow should run through the service task and complete");
        Object serviceResult = afterApprove.getVariables().get("serviceResult");
        assertTrue(serviceResult instanceof Map<?, ?>, "Service task must write its result under the output variable");
        assertEquals("e2e", ((Map<?, ?>) serviceResult).get("route"),
                "Echo executor must reflect the configured input back into variables");

        assertNotNull(afterApprove.getTrace(), "Execution trace must be present");
        assertFalse(afterApprove.getTrace().isEmpty(), "Trace must record node executions");
        assertNotNull(afterApprove.getApprovalAuditDelta(), "Approval action history must be present");
        assertFalse(afterApprove.getApprovalAuditDelta().isEmpty(),
                "Approval action history must record the approve action");
    }

    @Test
    void parallelBranchesSuspendingOnDifferentWaitsParkTheInstanceWithoutFailing() {
        long designId = 7002L;
        publishService.publish(designId, parallelMixedWaitFlow());

        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setDesignId(designId);
        startRequest.setInitiatorId("initiator-1");

        FlowExecutionState state = runtimeEngine.start(startRequest);

        // Regression: before waits were modelled as a token list, one parallel branch suspending
        // on an approval (WAITING_APPROVAL) and another on a timer (WAITING_TIMER) produced an
        // illegal status transition that failed the whole instance. Both now share WAITING.
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus(), "instance parks, does not fail");
        assertNull(state.getErrorMessage(), "no illegal-transition failure");
        assertEquals(1, state.getPendingApprovals().size(), "approval branch parked as a pending approval");
        assertTrue(state.getWaitTokens().stream().anyMatch(t -> t.getType() == WaitType.TIMER),
                "timer branch parked as a timer wait token");
    }

    @Test
    void returnValueNodePopulatesSynchronousReturnData() {
        long designId = 7003L;
        publishService.publish(designId, returnValueFlow());

        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setDesignId(designId);
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
                .code("e2e-return-value")
                .name("E2E Return Value")
                .scenario(FlowScenario.PROCESS)
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

    private static DesignFlowDefinition parallelMixedWaitFlow() {
        return DesignFlowDefinition.builder()
                .code("e2e-parallel-mixed-wait")
                .name("E2E Parallel Mixed Wait")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("fork", FlowNodeType.PARALLEL_FORK),
                                approvalNode("approval", APPROVER),
                                timerNode("timer", 3600L),
                                node("join", FlowNodeType.PARALLEL_JOIN),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "fork"),
                                edge("e2", "fork", "approval"),
                                edge("e3", "fork", "timer"),
                                edge("e4", "approval", "join"),
                                edge("e5", "timer", "join"),
                                edge("e6", "join", "end")
                        ))
                        .build())
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
        long designId = 7004L;
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
        new FlowPublishServiceImpl(compiler, localBundles).publish(designId, failingFlow());

        FlowStartRequest request = new FlowStartRequest();
        request.setDesignId(designId);
        request.setInitiatorId("initiator-1");

        // The failing node propagates, so start() rethrows — callers (e.g. trigger dispatch) rely on
        // this to record the failure. But the FAILED instance is still persisted (in production via
        // its own transaction, so it survives the start rollback).
        assertThrows(RuntimeException.class, () -> localEngine.start(request));

        List<FlowExecutionState> failed = localStore.listByStatus(FlowExecutionStatus.FAILED);
        assertEquals(1, failed.size(), "a FAILED instance must be recorded even when start fails");
        assertEquals("e2e-failing", failed.get(0).getFlowCode());
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
                .code("e2e-failing")
                .name("E2E Failing")
                .scenario(FlowScenario.PROCESS)
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
                .code("e2e-approval-then-service")
                .name("E2E Approval Then Service")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                approvalNode("approval", APPROVER),
                                taskNode("service", FlowNodeType.CALL_SERVICE,
                                        Map.of("beanName", "echoService", "methodName", "run", "route", "e2e"),
                                        "serviceResult"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "service"),
                                edge("e3", "service", "end")
                        ))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphNode approvalNode(String id, String approverId) {
        return FlowGraphNode.builder()
                .id(id)
                .type(FlowNodeType.APPROVAL)
                .label(id)
                .config(Map.of("approvers", List.of(approverId)))
                .build();
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
