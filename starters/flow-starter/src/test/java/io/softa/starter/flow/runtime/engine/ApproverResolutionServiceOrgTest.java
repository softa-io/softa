package io.softa.starter.flow.runtime.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.TestActorAccounts;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Tests for org-aware approver resolution in {@link ApproverResolutionService}.
 */
class ApproverResolutionServiceOrgTest {

    private ApproverResolutionService service;
    private OrganizationService orgService;

    @BeforeEach
    void setUp() throws Exception {
        service = new ApproverResolutionService();
        orgService = mock(OrganizationService.class);
        // Inject via reflection since it's @Autowired(required=false)
        var field = ApproverResolutionService.class.getDeclaredField("organizationService");
        field.setAccessible(true);
        field.set(service, orgService);
    }

    // ── Supervisor ──────────────────────────────────────────────────────

    @Test
    void supervisorIsTheSubjectsByDefault() {
        // HR (account 100, employee 1000) submits for employee 5000.
        when(orgService.getSupervisorOfEmployee(5000L, 2)).thenReturn(new ApproverInfo(200L, "Subject's manager"));

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor", "level", 2));
        List<String> result = service.resolveApprovers(node, varsWithSubject("100", 5000L));

        assertEquals(List.of("200"), result);
        verify(orgService, never()).getEmployeeIdOfUser(any());
    }

    @Test
    void supervisorRelativeToTheInitiatorIsTheSubmittersOwn() {
        when(orgService.getEmployeeIdOfUser(100L)).thenReturn(1000L);
        when(orgService.getSupervisorOfEmployee(1000L, 1)).thenReturn(new ApproverInfo(300L, "HR's manager"));

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor", "relativeTo", "INITIATOR"));

        assertEquals(List.of("300"), service.resolveApprovers(node, varsWithSubject("100", 5000L)));
    }

    @Test
    void withoutARecordTheSupervisorIsTheInitiatorsAtLevel1() {
        when(orgService.getEmployeeIdOfUser(100L)).thenReturn(1000L);
        when(orgService.getSupervisorOfEmployee(1000L, 1)).thenReturn(new ApproverInfo(200L, "Manager"));

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor"));
        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("200"), result);
    }

    @Test
    void supervisorReturnsEmptyWhenNoManager() {
        when(orgService.getSupervisorOfEmployee(5000L, 1)).thenReturn(null);

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor", "level", 1));
        List<String> result = service.resolveApprovers(node, varsWithSubject("100", 5000L));

        assertTrue(result.isEmpty());
    }

    /**
     * Why a step found nobody, for the refusal its submitter takes to HR: named for the source it finds people with
     * (zingkey-hcm#2447).
     */
    @Test
    void whyNobodyNamesWhatIsMissing() {
        when(orgService.getEmployeeNames(List.of(5000L))).thenReturn(Map.of(5000L, "Alice Tan"));
        when(orgService.getDeptIdOfEmployee(5000L)).thenReturn(10L);
        when(orgService.getDeptNames(List.of(10L))).thenReturn(Map.of(10L, "Finance"));
        // The submitter's account has no employee profile.
        when(orgService.getEmployeeIdOfUser(100L)).thenReturn(null);

        assertEquals("Alice Tan has no manager who can approve",
                service.whyNobody(approvalNode(Map.of("type", "Supervisor")), varsWithSubject("100", 5000L)));
        assertEquals("Alice Tan has no manager 2 levels up who can approve",
                service.whyNobody(approvalNode(Map.of("type", "Supervisor", "level", 2)), varsWithSubject("100", 5000L)));
        assertEquals("the submitter has no employee profile",
                service.whyNobody(approvalNode(Map.of("type", "Supervisor", "relativeTo", "INITIATOR")),
                        varsWithInitiator("100")));
        assertEquals("Finance and the departments above it have no leader who can approve",
                service.whyNobody(approvalNode(Map.of("type", "DeptLeader")), varsWithSubject("100", 5000L)));
        assertEquals("nobody who can approve holds its roles",
                service.whyNobody(approvalNode(Map.of("type", "RoleQuery", "roleIds", List.of(1))), Map.of()));
    }

    // ── DeptLeader ──────────────────────────────────────────────────────

    @Test
    void deptLeaderResolvesFromExplicitDeptId() {
        when(orgService.getDeptLeader(10L))
                .thenReturn(new ApproverInfo(300L, "Leader"));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "DeptLeader",
                "deptScope", "SPECIFIC_DEPT",
                "deptId", 10));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("300"), result);
        verify(orgService).getDeptLeader(10L);
    }

    @Test
    void deptLeaderDefaultsToTheSubjectsDept() {
        when(orgService.getDeptIdOfEmployee(5000L)).thenReturn(10L);
        when(orgService.getDeptLeader(10L))
                .thenReturn(new ApproverInfo(300L, "Leader"));

        CompiledFlowNode node = approvalNode(Map.of("type", "DeptLeader"));
        List<String> result = service.resolveApprovers(node, varsWithSubject("100", 5000L));

        assertEquals(List.of("300"), result);
    }

    @Test
    void deptLeaderOfTheDepartmentAVariableHolds() {
        // A transfer's receiving department.
        when(orgService.getDeptLeader(20L)).thenReturn(new ApproverInfo(310L, "Receiving leader"));

        CompiledFlowNode node = approvalNode(Map.of("type", "DeptLeader",
                "deptScope", "VARIABLE_DEPT", "deptVariable", "toDeptId"));
        Map<String, Object> vars = varsWithSubject("100", 5000L);
        vars.put("toDeptId", 20L);

        assertEquals(List.of("310"), service.resolveApprovers(node, vars));
    }

    @Test
    void deptHrbpDefaultsToTheSubjectsDept() {
        when(orgService.getDeptIdOfEmployee(5000L)).thenReturn(10L);
        when(orgService.getDeptHrbp(10L)).thenReturn(new ApproverInfo(400L, "HRBP"));

        CompiledFlowNode node = approvalNode(Map.of("type", "DeptHrbp"));

        assertEquals(List.of("400"), service.resolveApprovers(node, varsWithSubject("100", 5000L)));
    }

    // ── RoleQuery ───────────────────────────────────────────────────────

    @Test
    void roleQueryResolvesHoldersOfRolesNamedByIdAndByCode() {
        when(orgService.getUsersByRoleIds(List.of(9L)))
                .thenReturn(List.of(new ApproverInfo(500L, "Admin"), new ApproverInfo(600L, "Clerk")));
        when(orgService.getUsersByRoleCodes(List.of("HR_ADMIN")))
                .thenReturn(List.of(new ApproverInfo(500L, "Admin"), new ApproverInfo(700L, "Officer")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "RoleQuery",
                "roleCodes", List.of("HR_ADMIN"),
                "roleIds", List.of(9)));

        assertEquals(List.of("500", "600", "700"), service.resolveApprovers(node, varsWithInitiator("100")));
    }

    @Test
    void roleQueryResolvesAllUsersWithRole() {
        when(orgService.getUsersByRoleIds(List.of(1L, 2L)))
                .thenReturn(List.of(
                        new ApproverInfo(400L, "User1", 10L, "Dept1"),
                        new ApproverInfo(401L, "User2", 20L, "Dept2")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of(1, 2)));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("400", "401"), result);
    }

    @Test
    void roleQueryFiltersByInitiatorDept() {
        when(orgService.getUsersByRoleIds(List.of(1L)))
                .thenReturn(List.of(
                        new ApproverInfo(400L, "User1", 10L, "Dept1"),
                        new ApproverInfo(401L, "User2", 20L, "Dept2")));
        when(orgService.getEmployeeIdOfUser(100L)).thenReturn(1000L);
        when(orgService.getDeptIdOfEmployee(1000L)).thenReturn(10L);

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of(1),
                "deptScope", "INITIATOR_DEPT"));

        List<String> result = service.resolveApprovers(node, varsWithSubject("100", 5000L));

        assertEquals(List.of("400"), result);
    }

    @Test
    void roleQueryNarrowedToADepartmentThatCannotBeToldResolvesNobody() {
        when(orgService.getUsersByRoleIds(List.of(1L)))
                .thenReturn(List.of(new ApproverInfo(400L, "User1", 10L, "Dept1")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of(1),
                "deptScope", "SUBJECT_DEPT"));

        assertTrue(service.resolveApprovers(node, varsWithSubject("100", 5000L)).isEmpty());
    }

    @Test
    void roleQueryFiltersBySpecificDept() {
        when(orgService.getUsersByRoleIds(List.of(1L)))
                .thenReturn(List.of(
                        new ApproverInfo(400L, "User1", 10L, "Dept1"),
                        new ApproverInfo(401L, "User2", 20L, "Dept2")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of(1),
                "deptScope", "SPECIFIC_DEPT",
                "deptId", 20));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("401"), result);
    }

    // ── Position ────────────────────────────────────────────────────────

    @Test
    void positionResolvesUsersByPositionIds() {
        when(orgService.getUsersByPositionIds(List.of(5L, 6L)))
                .thenReturn(List.of(
                        new ApproverInfo(500L, "Pos1"),
                        new ApproverInfo(501L, "Pos2")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "Position",
                "positionIds", List.of(5, 6)));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("500", "501"), result);
    }

    // ── Department ──────────────────────────────────────────────────────

    @Test
    void departmentResolvesUsersByDeptIds() {
        when(orgService.getUsersByDeptIds(List.of(10L, 20L), false))
                .thenReturn(List.of(
                        new ApproverInfo(600L, "Emp1"),
                        new ApproverInfo(601L, "Emp2")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "Department",
                "deptIds", List.of(10, 20)));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("600", "601"), result);
    }

    @Test
    void departmentIncludesSubDepts() {
        when(orgService.getUsersByDeptIds(List.of(10L), true))
                .thenReturn(List.of(
                        new ApproverInfo(600L, "Emp1"),
                        new ApproverInfo(601L, "Emp2")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "Department",
                "deptIds", List.of(10),
                "includeSubDepts", true));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("600", "601"), result);
    }

    @Test
    void departmentUsesInitiatorDeptScope() {
        when(orgService.getEmployeeIdOfUser(100L)).thenReturn(1000L);
        when(orgService.getDeptIdOfEmployee(1000L)).thenReturn(10L);
        when(orgService.getUsersByDeptIds(List.of(10L), false))
                .thenReturn(List.of(new ApproverInfo(600L, "Emp1")));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "Department",
                "deptScope", "INITIATOR_DEPT"));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("600"), result);
    }

    // ── Graceful fallback when OrganizationService is null ──────────────

    @Test
    void supervisorReturnsEmptyWhenOrgServiceIsNull() throws Exception {
        // Reset organizationService to null
        var field = ApproverResolutionService.class.getDeclaredField("organizationService");
        field.setAccessible(true);
        field.set(service, null);

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor", "level", 1));
        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertTrue(result.isEmpty());
    }

    // ── Specific people ─────────────────────────────────────────────────

    @Test
    void specificPeopleAreTheUsersTheSourceNames() {
        CompiledFlowNode node = approvalNode(Map.of("type", "Users", "userIds", List.of("alice", " ", "bob", "alice")));

        assertEquals(List.of("alice", "bob"), service.resolveApprovers(node, varsWithInitiator("100")));
        assertFalse(service.hasDynamicApproverSource(node), "named, not found when the step is reached");
        assertTrue(service.hasDynamicApproverSource(approvalNode(Map.of("type", "Supervisor"))));
    }

    // ── Groups ──────────────────────────────────────────────────────────

    /** A step's groups make one pool: everyone they name or find, each once, in the groups' order. */
    @Test
    void aStepsGroupsMakeOnePool() {
        when(orgService.getSupervisorOfEmployee(5000L, 1)).thenReturn(new ApproverInfo(200L, "Manager"));
        CompiledFlowNode node = approvalNode(List.of(
                Map.of("type", "Supervisor"),
                Map.of("type", "Users", "userIds", List.of("300", "200"))));

        assertEquals(List.of("200", "300"), service.resolveApprovers(node, varsWithSubject("100", 5000L)));
        assertTrue(service.hasDynamicApproverSource(node), "one group finds its people when the step is reached");
        assertFalse(service.hasDynamicApproverSource(approvalNode(List.of(
                Map.of("type", "Users", "userIds", List.of("300")),
                Map.of("type", "Users", "userIds", List.of("400"))))), "every group names its people");
    }

    /** A group that finds nobody leaves the others to decide; the pool is empty only when none finds anyone. */
    @Test
    void aGroupThatFindsNobodyLeavesTheOthers() {
        when(orgService.getSupervisorOfEmployee(5000L, 1)).thenReturn(null);
        when(orgService.getEmployeeNames(List.of(5000L))).thenReturn(Map.of(5000L, "Alice Tan"));
        CompiledFlowNode withHr = approvalNode(List.of(
                Map.of("type", "Supervisor"),
                Map.of("type", "Users", "userIds", List.of("300"))));

        assertEquals(List.of("300"), service.resolveApprovers(withHr, varsWithSubject("100", 5000L)));

        CompiledFlowNode nobody = approvalNode(List.of(
                Map.of("type", "Supervisor"),
                Map.of("type", "RoleQuery", "roleIds", List.of(1))));
        assertEquals(List.of(), service.resolveApprovers(nobody, varsWithSubject("100", 5000L)));
        assertEquals("Alice Tan has no manager who can approve; nobody who can approve holds its roles",
                service.whyNobody(nobody, varsWithSubject("100", 5000L)), "each group says why");
        assertEquals("it names no approver", service.whyNobody(approvalNode(List.of()), Map.of()));
    }

    @Test
    void approversWhoCannotActAreDroppedFromEverySource() throws Exception {
        var field = ApproverResolutionService.class.getDeclaredField("actorAccounts");
        field.setAccessible(true);
        field.set(service, TestActorAccounts.of(Map.of(2002L, "Frozen"), Map.of()));
        CompiledFlowNode named = approvalNode(Map.of("type", "Users", "userIds", List.of("2001", "2002")));
        Map<String, Object> vars = varsWithInitiator("100");
        when(orgService.getUsersByRoleIds(List.of(1L)))
                .thenReturn(List.of(new ApproverInfo(2002L, "Frozen"), new ApproverInfo(2003L, "Finn")));

        assertEquals(List.of("2001"), service.resolveApprovers(named, vars), "specific people");
        assertEquals(List.of("2003"), service.resolveApprovers(approvalNode(Map.of("type", "RoleQuery",
                "roleIds", List.of(1))), vars), "a role's holders");
        FlowExecutionState state = FlowExecutionState.builder().initiatorId("100").variables(new LinkedHashMap<>()).build();
        assertEquals(List.of(), service.resolvePeople(Map.of("type", "Users", "userIds", List.of("2002")), state),
                "a CC rule's recipients");
    }

    // ── The employee the record is about ────────────────────────────────

    /**
     * Finding approvers leaves nobody out for being who the record is about: whether they are asked is the flow's
     * "when the submitter is an approver" setting when they submitted it themselves — a general manager's own request
     * on a step naming the general manager passes by default — and they are asked like anyone else when someone else
     * submitted it ({@link ApproverDedupService}).
     */
    @Test
    void theEmployeeStaysAmongAStepsApprovers() {
        when(orgService.getUserIdOfEmployee(5000L)).thenReturn(900L);
        when(orgService.getDeptIdOfEmployee(5000L)).thenReturn(10L);
        when(orgService.getDeptLeader(10L)).thenReturn(new ApproverInfo(900L, "Ann, who leads the department"));
        when(orgService.getUsersByRoleIds(List.of(1L)))
                .thenReturn(List.of(new ApproverInfo(900L, "Ann"), new ApproverInfo(902L, "Ben")));

        assertEquals(List.of("900"), service.resolveApprovers(approvalNode(Map.of("type", "Users",
                "userIds", List.of("900"))), varsWithSubject("900", 5000L)), "a step naming only them");
        assertEquals(List.of("900"), service.resolveApprovers(approvalNode(Map.of("type", "DeptLeader")),
                varsWithSubject("100", 5000L)), "their own department's leader");
        assertEquals(List.of("900", "902"), service.resolveApprovers(approvalNode(Map.of("type", "RoleQuery",
                "roleIds", List.of(1))), varsWithSubject("900", 5000L)), "a role's holders");
    }

    /** A CC rule's recipients are found the same way, the employee the record is about among them. */
    @Test
    void ccRecipientsKeepTheEmployee() {
        when(orgService.getUserIdOfEmployee(5000L)).thenReturn(900L);
        FlowExecutionState state = FlowExecutionState.builder().initiatorId("100")
                .variables(new LinkedHashMap<>(Map.of(ApproverResolutionService.SUBJECT_ID_KEY, 5000L))).build();

        assertEquals(List.of("900"), service.resolvePeople(Map.of("type", "Users", "userIds", List.of("900")), state));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private CompiledFlowNode approvalNode(Map<String, Object> approverSource) {
        return approvalNode(List.of(approverSource));
    }

    /** A step whose groups of approvers are {@code groups}. */
    private CompiledFlowNode approvalNode(List<Map<String, Object>> groups) {
        ApprovalNodeConfig cfg = ApprovalNodeConfig.builder()
                .approverSources(groups)
                .approvalMode(VoteThresholdMode.ANY_ONE)
                .build();
        return CompiledFlowNode.builder()
                .nodeId("approval-1")
                .type(FlowNodeType.APPROVAL)
                .parsedConfig(cfg)
                .build();
    }

    /** A record's approval: submitted by {@code initiatorId}, about employee {@code subjectId}. */
    private Map<String, Object> varsWithSubject(String initiatorId, Long subjectId) {
        Map<String, Object> vars = varsWithInitiator(initiatorId);
        vars.put(ApproverResolutionService.SUBJECT_ID_KEY, subjectId);
        return vars;
    }

    private Map<String, Object> varsWithInitiator(String initiatorId) {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> approverContext = new LinkedHashMap<>();
        approverContext.put(ApproverResolutionService.INITIATOR_ID_KEY, initiatorId);
        vars.put(ApproverResolutionService.APPROVER_CONTEXT_KEY, approverContext);
        return vars;
    }
}
