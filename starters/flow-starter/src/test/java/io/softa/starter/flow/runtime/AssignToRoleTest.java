package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
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
import io.softa.starter.flow.runtime.exception.FlowNoApproverException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A step that finds nobody, under {@code AssignToRole}, goes to the holders of its roles who can act — the submitter
 * and the employee the record is about left out — and any one of them decides it, whatever its own modes say. With
 * nobody left it refuses the action that reached it, naming the roles.
 */
class AssignToRoleTest {

    private static final String SUBMITTER = "1001";
    /** The employee the record is about, and their account. */
    private static final long SUBJECT = 5001L;
    private static final String SUBJECT_USER = "5005";

    private final OrganizationService organization = mock(OrganizationService.class);
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore();
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithOrganization(bundleRegistry, instanceStore,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler, organization);

    @BeforeEach
    void organization() {
        // The submitter has no supervisor: the step's own source finds nobody.
        when(organization.getSupervisorOfEmployee(anyLong(), anyInt())).thenReturn(null);
        when(organization.getEmployeeIdOfUser(anyLong())).thenReturn(4001L);
        when(organization.getUserIdOfEmployee(SUBJECT)).thenReturn(Long.valueOf(SUBJECT_USER));
        when(organization.getRoleNames(any(), any())).thenReturn(List.of("HR Admin"));
        new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry).publish("leave", flow());
    }

    @Test
    void aStepThatFindsNobodyGoesToTheRolesHoldersAnyOneOfWhomDecides() {
        holders(SUBMITTER, "3001", SUBJECT_USER, "3002");

        FlowExecutionState state = engine.startRecordApproval(start());

        PendingApproval step = state.getPendingApprovals().getFirst();
        assertEquals(List.of("3001", "3002"), step.getApprovers(), "the submitter and the subject are left out");
        assertEquals(1, step.getRequiredApprovalCount(), "any one, though the node asks all");
        assertEquals(List.of("HR Admin"), step.getAssignedRoles());

        approve(state, "3002");

        assertEquals(FlowExecutionStatus.COMPLETED, instanceStore.get(state.getInstanceId()).orElseThrow().getStatus());
    }

    @Test
    void withNobodyLeftInTheRolesTheSubmissionIsRefusedNamingThem() {
        holders(SUBMITTER, SUBJECT_USER);

        FlowNoApproverException refused = assertThrows(FlowNoApproverException.class,
                () -> engine.startRecordApproval(start()));

        assertEquals("No approver was found for step 'HR approval', and nobody in HR Admin can take it but the "
                + "submitter or the employee the record is about. Ask your HR administrator to set one up, then try "
                + "again.", refused.getMessage());
    }

    @Test
    void aStepThatFindsSomeoneIsNotHandedToTheRoles() {
        holders("3001");
        when(organization.getSupervisorOfEmployee(anyLong(), anyInt())).thenReturn(new ApproverInfo(2001L, "Bob"));

        PendingApproval step = engine.startRecordApproval(start()).getPendingApprovals().getFirst();

        assertEquals(List.of("2001"), step.getApprovers());
        assertNull(step.getAssignedRoles());
    }

    @Test
    void handingToRolesNamesAtLeastOne() {
        DesignFlowDefinition design = flow();
        design.getGraph().getNodes().stream().filter(node -> "hr".equals(node.getId())).findFirst().orElseThrow()
                .setConfig(Map.of("approverSources", List.of(Map.of("type", "Supervisor")), "emptyApproverStrategy", "AssignToRole"));

        List<CompileDiagnostic> diagnostics = new DefaultFlowCompiler().validate(design);

        assertEquals(List.of("MISSING_EMPTY_APPROVER_ROLES"), diagnostics.stream().map(CompileDiagnostic::code).toList());
    }

    private void holders(String... accounts) {
        List<ApproverInfo> holders = new ArrayList<>();
        for (String account : accounts) {
            holders.add(new ApproverInfo(Long.valueOf(account), "user " + account));
        }
        when(organization.getUsersByRoleCodes(List.of("HR_ADMIN"))).thenReturn(holders);
    }

    private static FlowStartRequest start() {
        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId("leave");
        request.setInitiatorId(SUBMITTER);
        request.setVariables(Map.of("_subjectId", SUBJECT));
        return request;
    }

    private void approve(FlowExecutionState state, String actor) {
        FlowApproveRequest request = new FlowApproveRequest();
        request.setInstanceId(state.getInstanceId());
        request.setNodeId("hr");
        request.setActorId(actor);
        request.setComment("ok");
        engine.approve(request);
    }

    /** START → HR approval: the submitter's supervisor, all of them, else the HR admins → END. */
    private static DesignFlowDefinition flow() {
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(
                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                FlowGraphNode.builder().id("hr").type(FlowNodeType.APPROVAL).label("HR approval")
                        .config(Map.of("approverSources", List.of(Map.of("type", "Supervisor")), "approvalMode", "Unanimous",
                                "emptyApproverStrategy", "AssignToRole", "emptyApproverRoleCodes", List.of("HR_ADMIN")))
                        .build(),
                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()));
        List<FlowGraphEdge> edges = List.of(
                FlowGraphEdge.builder().id("e1").source("start").target("hr").build(),
                FlowGraphEdge.builder().id("e2").source("hr").target("end").build());
        return DesignFlowDefinition.builder()
                .name("Leave")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }
}
