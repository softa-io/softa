package io.softa.starter.flow.runtime.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void supervisorResolvesDirectManager() {
        when(orgService.getSupervisor(100L, 1))
                .thenReturn(new ApproverInfo(200L, "Manager"));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "Supervisor",
                "level", 1));

        Map<String, Object> vars = varsWithInitiator("100");
        List<String> result = service.resolveApprovers(node, vars);

        assertEquals(List.of("200"), result);
        verify(orgService).getSupervisor(100L, 1);
    }

    @Test
    void supervisorDefaultsToLevel1() {
        when(orgService.getSupervisor(100L, 1))
                .thenReturn(new ApproverInfo(200L, "Manager"));

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor"));
        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("200"), result);
    }

    @Test
    void supervisorReturnsEmptyWhenNoManager() {
        when(orgService.getSupervisor(100L, 1)).thenReturn(null);

        CompiledFlowNode node = approvalNode(Map.of("type", "Supervisor", "level", 1));
        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertTrue(result.isEmpty());
    }

    // ── DeptLeader ──────────────────────────────────────────────────────

    @Test
    void deptLeaderResolvesFromExplicitDeptId() {
        when(orgService.getDeptLeader(10L))
                .thenReturn(new ApproverInfo(300L, "Leader"));

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "DeptLeader",
                "deptId", 10));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("300"), result);
        verify(orgService).getDeptLeader(10L);
    }

    @Test
    void deptLeaderDefaultsToInitiatorDept() {
        when(orgService.getUserDeptId(100L)).thenReturn(10L);
        when(orgService.getDeptLeader(10L))
                .thenReturn(new ApproverInfo(300L, "Leader"));

        CompiledFlowNode node = approvalNode(Map.of("type", "DeptLeader"));
        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("300"), result);
    }

    // ── RoleQuery ───────────────────────────────────────────────────────

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
        when(orgService.getUserDeptId(100L)).thenReturn(10L);

        CompiledFlowNode node = approvalNode(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of(1),
                "deptScope", "INITIATOR_DEPT"));

        List<String> result = service.resolveApprovers(node, varsWithInitiator("100"));

        assertEquals(List.of("400"), result);
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
        when(orgService.getUserDeptId(100L)).thenReturn(10L);
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

    // ── Backward compatibility: existing types still work ───────────────

    @Test
    void variableListStillWorks() {
        CompiledFlowNode node = approvalNode(Map.of(
                "type", "VariableList",
                "variable", "myApprovers"));

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("myApprovers", List.of("u1", "u2"));

        List<String> result = service.resolveApprovers(node, vars);

        assertEquals(List.of("u1", "u2"), result);
    }

    @Test
    void staticApproversStillWork() {
        ApprovalNodeConfig cfg = ApprovalNodeConfig.builder()
                .approvers(List.of("alice", "bob"))
                .approvalMode(VoteThresholdMode.ANY_ONE)
                .build();
        CompiledFlowNode node = CompiledFlowNode.builder()
                .nodeId("a1")
                .type(FlowNodeType.APPROVAL)
                .parsedConfig(cfg)
                .build();

        List<String> result = service.resolveApprovers(node);

        assertEquals(List.of("alice", "bob"), result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private CompiledFlowNode approvalNode(Map<String, Object> approverSource) {
        ApprovalNodeConfig cfg = ApprovalNodeConfig.builder()
                .approverSource(approverSource)
                .approvalMode(VoteThresholdMode.ANY_ONE)
                .build();
        return CompiledFlowNode.builder()
                .nodeId("approval-1")
                .type(FlowNodeType.APPROVAL)
                .parsedConfig(cfg)
                .build();
    }

    private Map<String, Object> varsWithInitiator(String initiatorId) {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> approverContext = new LinkedHashMap<>();
        approverContext.put(ApproverResolutionService.INITIATOR_ID_KEY, initiatorId);
        vars.put(ApproverResolutionService.APPROVER_CONTEXT_KEY, approverContext);
        return vars;
    }
}
