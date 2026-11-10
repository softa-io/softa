package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowRetryRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.PersistingFlowInstanceStore;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The steps after an approval failing (zingkey-hcm#2385): the approval stands — the approver's request goes
 * through — and the instance fails at the step that failed, remembering where to run again from, its initiator and
 * the tenant's administrators told. An administrator runs the steps again: the approval goes on, or fails again. An
 * approval flow holds people and routes only, so what stops it after an approval is the next step finding nobody to
 * approve it — here, its approver's account frozen — or a route out of the approval that cannot be decided (#2382).
 */
class StepsAfterApprovalScenarioTest {

    private static final String FLOW_ID = "9500";
    private static final String INITIATOR = "initiator-1";
    private static final String ALICE = "1001";
    private static final String BOB = "1002";
    private static final String ADMIN = "hr-admin";

    /** Accounts that cannot act now, by id: read at every resolution, so a test unfreezes Bob by removing him. */
    private final Map<Long, String> unusable = new HashMap<>(Map.of(Long.valueOf(BOB), "Frozen"));
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final PersistingFlowInstanceStore store = new PersistingFlowInstanceStore();
    private final List<FlowNotificationEvent> told = new ArrayList<>();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithAccounts(
            bundleRegistry,
            store,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            told::add,
            TestActorAccounts.of(unusable, Map.of(ALICE, "Alice Tan", BOB, "Bob Lee")));

    @Test
    void aStepFailingAfterTheApprovalFailsTheInstanceAndTheApprovalStands() {
        FlowExecutionState started = start();

        FlowExecutionState state = engine.approve(approve(started.getInstanceId(), ALICE, "approval"));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(state.getFailedNodeId()).isEqualTo("payroll");
        assertThat(state.getErrorMessage()).contains("No approver was found for step 'Payroll'");
        assertThat(state.getRetryAfterNodeId()).isEqualTo("approval");
        // As the approval left it: approved, and nothing after it done.
        assertThat(state.getCompletedNodeIds()).contains("approval").doesNotContain("payroll", "end");
        assertThat(state.getPendingApprovals()).isEmpty();
        assertThat(store.get(started.getInstanceId()).orElseThrow().getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(told).filteredOn(FlowNotificationEvent.FlowFailed.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((FlowNotificationEvent.FlowFailed) event).failedNodeId()).isEqualTo("payroll"));
    }

    @Test
    void runAgainTheApprovalGoesOnOnceTheStepsGoThrough() {
        FlowExecutionState started = start();
        engine.approve(approve(started.getInstanceId(), ALICE, "approval"));
        unusable.remove(Long.valueOf(BOB));

        FlowExecutionState state = engine.retry(retry(started.getInstanceId()));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.WAITING);
        assertThat(state.getRetryAfterNodeId()).isNull();
        assertThat(state.getFailedNodeId()).isNull();
        assertThat(state.getErrorMessage()).isNull();
        assertThat(state.getPendingApprovals()).singleElement()
                .satisfies(pending -> assertThat(pending.getApprovers()).containsExactly(BOB));
        ApprovalActionAuditEntry retried = state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.RETRY.equals(entry.getAction())).findFirst().orElseThrow();
        assertThat(retried.getActorId()).isEqualTo(ADMIN);
        assertThat(retried.getNodeId()).isEqualTo("approval");

        FlowExecutionState done = engine.approve(approve(started.getInstanceId(), BOB, "payroll"));
        assertThat(done.getStatus()).isEqualTo(FlowExecutionStatus.COMPLETED);
        assertThat(done.getCompletedNodeIds()).contains("approval", "payroll", "end");
    }

    @Test
    void runAgainAndFailingAgainTheInstanceFailsAgain() {
        FlowExecutionState started = start();
        engine.approve(approve(started.getInstanceId(), ALICE, "approval"));

        FlowExecutionState state = engine.retry(retry(started.getInstanceId()));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(state.getRetryAfterNodeId()).isEqualTo("approval");
        assertThat(state.getFailedNodeId()).isEqualTo("payroll");
        assertThat(told).filteredOn(FlowNotificationEvent.FlowFailed.class::isInstance).hasSize(2);
    }

    @Test
    void aRouteThatCannotBeDecidedAfterTheApprovalFailsTheInstanceAndTheApprovalStands() {
        // The amount kept as text and compared with a number (zingkey-hcm#2382): the route out of the approval cannot
        // be decided. Nothing the approver can put right, so the approval stands and the instance fails at its step.
        unusable.clear();
        publishService.publish(FLOW_ID, routedFlow());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        start.setVariables(Map.of("amount", "a lot"));
        FlowExecutionState started = engine.start(start);

        FlowExecutionState state = engine.approve(approve(started.getInstanceId(), ALICE, "approval"));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(state.getFailedNodeId()).isEqualTo("approval");
        assertThat(state.getRetryAfterNodeId()).isEqualTo("approval");
        assertThat(state.getCompletedNodeIds()).contains("approval").doesNotContain("payroll", "end");
        assertThat(store.get(started.getInstanceId()).orElseThrow().getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(told).filteredOn(FlowNotificationEvent.FlowFailed.class::isInstance).hasSize(1);
    }

    @Test
    void onlyAnApprovalThatFailedInTheStepsAfterAnApprovalRunsAgain() {
        FlowExecutionState waiting = start();

        assertThrows(FlowActionValidationException.class, () -> engine.retry(retry(waiting.getInstanceId())));

        // Failed, but not after an approval: there is nowhere to run again from.
        FlowExecutionState failed = store.get(waiting.getInstanceId()).orElseThrow();
        failed.setStatus(FlowExecutionStatus.FAILED);
        store.save(failed);
        assertThrows(FlowActionValidationException.class, () -> engine.retry(retry(waiting.getInstanceId())));
    }

    private FlowExecutionState start() {
        publishService.publish(FLOW_ID, flow());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start);
    }

    private static FlowApproveRequest approve(String instanceId, String actorId, String nodeId) {
        FlowApproveRequest request = new FlowApproveRequest();
        request.setInstanceId(instanceId);
        request.setNodeId(nodeId);
        request.setActorId(actorId);
        return request;
    }

    private static FlowRetryRequest retry(String instanceId) {
        FlowRetryRequest request = new FlowRetryRequest();
        request.setInstanceId(instanceId);
        request.setActorId(ADMIN);
        return request;
    }

    private static FlowGraphNode approvalStep(String id, String label, String approver) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(label)
                .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of(approver))),
                        "approvalMode", "AnyOne", "emptyApproverStrategy", "Error"))
                .build();
    }

    /** Start, Alice's approval, then Bob's — payroll's — and the end. */
    private static DesignFlowDefinition flow() {
        return DesignFlowDefinition.builder()
                .name("Steps after approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                approvalStep("approval", "Manager", ALICE),
                                approvalStep("payroll", "Payroll", BOB),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("payroll").build(),
                                FlowGraphEdge.builder().id("e3").source("payroll").target("end").build()))
                        .build())
                .build();
    }

    /** Start, Alice's approval, then Bob's for large amounts, else the end. */
    private static DesignFlowDefinition routedFlow() {
        return DesignFlowDefinition.builder()
                .name("Steps after approval, routed")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                approvalStep("approval", "Manager", ALICE),
                                approvalStep("payroll", "Payroll", BOB),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("payroll")
                                        .conditionExpression("amount > 5000").build(),
                                FlowGraphEdge.builder().id("e3").source("approval").target("end").build(),
                                FlowGraphEdge.builder().id("e4").source("payroll").target("end").build()))
                        .build())
                .build();
    }
}
