package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An approval writes to its record with the approver's own permission checks set aside: confirming the
 * actor as the step's approver is the grant. A record's write rules (its {@code ModelWriteValidator}s)
 * therefore see the approver's form edits as the system's, not as a person editing someone's record.
 */
class ApprovalWritesAsApproverTest {

    private static final String FLOW_ID = "9300";
    private static final String APPROVER = "manager-1";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    @SuppressWarnings("unchecked")
    private final ModelService<Long> records = mock(ModelService.class);

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler, state -> {}, state -> {}, records);

    @Test
    void theApproversFormEditsAreWrittenPastTheirOwnPermissions() {
        publishService.publish(FLOW_ID, flowWithEditableNote());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId("initiator-1");
        start.setModelName("ExpenseClaim");
        start.setRowId("7");
        FlowExecutionState started = engine.start(start);
        List<Boolean> skipped = new ArrayList<>();
        when(records.updateByFilter(eq("ExpenseClaim"), any(Filters.class), anyMap())).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return 1;
        });
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(APPROVER);
        approve.setFormData(Map.of("note", "checked the receipts"));
        Context approver = new Context();
        approver.setUserId(2001L);

        ContextHolder.runWith(approver, () -> engine.approve(approve));

        assertEquals(List.of(true), skipped);
    }

    private static DesignFlowDefinition flowWithEditableNote() {
        return DesignFlowDefinition.builder()
                .name("Approval Writes")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of(
                                                "approvers", List.of(APPROVER),
                                                "formPermissions", Map.of("note", "Editable")))
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
