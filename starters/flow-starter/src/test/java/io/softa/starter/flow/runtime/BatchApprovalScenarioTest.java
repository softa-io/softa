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
import io.softa.starter.flow.dto.FlowBatchActionResult;
import io.softa.starter.flow.dto.FlowBatchActionResult.Outcome;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowBatchActionRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.BatchApprovalExecutor;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Batch approver actions over real instances: each task acted on separately, one refusal changing nothing for
 * the others; a step a batch cannot act on skipped before the engine is asked.
 */
class BatchApprovalScenarioTest {

    private static final String APPROVER = "manager-1";
    private static final String INITIATOR = "initiator-1";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);
    private final BatchApprovalExecutor executor = new BatchApprovalExecutor(engine, bundleRegistry);

    private String start(String flowId) {
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(flowId);
        start.setInitiatorId(INITIATOR);
        return engine.start(start).getInstanceId();
    }

    private static FlowBatchActionRequest request(String comment, String... instanceIds) {
        FlowBatchActionRequest request = new FlowBatchActionRequest();
        request.setComment(comment);
        request.setTasks(java.util.Arrays.stream(instanceIds).map(instanceId -> {
            FlowBatchActionRequest.Task task = new FlowBatchActionRequest.Task();
            task.setInstanceId(instanceId);
            task.setNodeId("approval");
            return task;
        }).toList());
        return request;
    }

    @Test
    void eachTaskIsApprovedOnItsOwnAndARefusalLeavesTheOthersApproved() {
        publishService.publish("plain", flow(Map.of("approvers", List.of(APPROVER))));
        String first = start("plain");
        String second = start("plain");

        FlowBatchActionResult result = executor.execute(BatchApprovalExecutor.Action.APPROVE,
                request(null, first, "no-such-instance", second), APPROVER);

        assertEquals(2, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(List.of(Outcome.SUCCEEDED, Outcome.FAILED, Outcome.SUCCEEDED),
                result.items().stream().map(FlowBatchActionResult.Item::outcome).toList());
        assertNull(result.items().getFirst().message());
        assertEquals(FlowExecutionStatus.COMPLETED, engine.getInstance(first).orElseThrow().getStatus());
        assertEquals(FlowExecutionStatus.COMPLETED, engine.getInstance(second).orElseThrow().getStatus());
    }

    @Test
    void aStepThatAllowsNoReturnIsSkippedAndOneThatDoesIsReturned() {
        publishService.publish("no-return", flow(Map.of("approvers", List.of(APPROVER))));
        publishService.publish("returnable", flow(Map.of("approvers", List.of(APPROVER), "returnEnabled", true)));
        String kept = start("no-return");
        String returned = start("returnable");

        FlowBatchActionResult result = executor.execute(BatchApprovalExecutor.Action.RETURN,
                request("Please attach the receipt", kept, returned), APPROVER);

        assertEquals(1, result.skipped());
        assertEquals(1, result.succeeded());
        assertEquals("This step does not allow returns.", result.items().getFirst().message());
        assertEquals(FlowExecutionStatus.WAITING, engine.getInstance(kept).orElseThrow().getStatus());
        assertEquals(FlowExecutionStatus.RETURNED, engine.getInstance(returned).orElseThrow().getStatus());
    }

    @Test
    void aStepWithFieldsToFillInIsNeverActedOnInABatch() {
        publishService.publish("required", flow(Map.of("approvers", List.of(APPROVER),
                "formPermissions", Map.of("decision", "Required"))));
        String required = start("required");

        FlowBatchActionResult result = executor.execute(BatchApprovalExecutor.Action.REJECT,
                request("Not this time", required), APPROVER);

        assertEquals(1, result.skipped());
        assertEquals("This step has fields to fill in: open it to act on it.", result.items().getFirst().message());
        assertEquals(FlowExecutionStatus.WAITING, engine.getInstance(required).orElseThrow().getStatus());
    }

    @Test
    void aRejectionWithoutItsCommentIsRefusedTaskByTask() {
        publishService.publish("plain", flow(Map.of("approvers", List.of(APPROVER))));
        String instance = start("plain");

        FlowBatchActionResult result = executor.execute(BatchApprovalExecutor.Action.REJECT, request(null, instance),
                APPROVER);

        assertEquals(1, result.failed());
        assertEquals(FlowExecutionStatus.WAITING, engine.getInstance(instance).orElseThrow().getStatus());
    }

    private static DesignFlowDefinition flow(Map<String, Object> approvalConfig) {
        return DesignFlowDefinition.builder()
                .name("Batch")
                .scenario(FlowScenario.APPROVAL)
                .modelName("LeaveRequest").trigger(new SubmitTrigger())
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(approvalConfig).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
