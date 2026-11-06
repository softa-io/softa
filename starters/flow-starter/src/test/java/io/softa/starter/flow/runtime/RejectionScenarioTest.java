package io.softa.starter.flow.runtime;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A step has no reject threshold: one approver's rejection rejects the approval, however many approvals the step
 * counts — an all-approve step (会签) and a "two of three" step alike — and an approver who already approved the step
 * has had their say on it.
 */
class RejectionScenarioTest {

    private static final List<String> APPROVERS = List.of("1001", "1002", "1003");

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(bundleRegistry, new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

    @Test
    void oneRejectionRejectsAStepThatCountsSeveralApprovals() {
        FlowExecutionState state = start(Map.of("approvalMode", "MinCount", "minCount", 2,
                // A threshold a step was saved with before: ignored.
                "rejectMode", "MinCount", "rejectMinCount", 2));

        FlowExecutionState rejected = engine.reject(reject(state, "1001"));

        assertEquals(FlowExecutionStatus.REJECTED, rejected.getStatus());
    }

    @Test
    void anApproverWhoApprovedCannotThenRejectButAnotherCan() {
        FlowExecutionState state = start(Map.of("approvalMode", "Unanimous"));
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(state.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId("1001");
        engine.approve(approve);

        assertThrows(FlowActionValidationException.class, () -> engine.reject(reject(state, "1001")));
        assertEquals(FlowExecutionStatus.REJECTED, engine.reject(reject(state, "1002")).getStatus());
    }

    private static FlowRejectRequest reject(FlowExecutionState state, String actor) {
        FlowRejectRequest request = new FlowRejectRequest();
        request.setInstanceId(state.getInstanceId());
        request.setNodeId("approval");
        request.setActorId(actor);
        request.setComment("Not this time");
        return request;
    }

    private FlowExecutionState start(Map<String, Object> votes) {
        Map<String, Object> config = new java.util.LinkedHashMap<>(votes);
        config.put("approverSource", Map.of("type", "Users", "userIds", APPROVERS));
        publishService.publish("rejections", DesignFlowDefinition.builder()
                .name("Rejections")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(config).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId("rejections");
        start.setInitiatorId("initiator-1");
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        return state;
    }
}
