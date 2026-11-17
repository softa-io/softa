package io.softa.starter.flow.runtime.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.PersonFieldsFixture;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

import static io.softa.starter.flow.runtime.context.PersonFieldsFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * The person a field of the record names, as an approval step's group ({@code RecordField}), and the department a
 * field names for a department-based source ({@code VARIABLE_DEPT}): read from the record as its approval carries it,
 * on the record's own model ({@code _sourceModel}).
 */
class ApproverResolutionServiceRecordFieldTest {

    private ApproverResolutionService service;
    private OrganizationService orgService;
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() throws Exception {
        models = PersonFieldsFixture.mockModelManager();
        service = new ApproverResolutionService();
        orgService = mock(OrganizationService.class);
        var field = ApproverResolutionService.class.getDeclaredField("organizationService");
        field.setAccessible(true);
        field.set(service, orgService);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void anEmployeeFieldNamesTheEmployeesAccount() {
        when(orgService.getUserIdOfEmployee(7000L)).thenReturn(700L);

        assertEquals(List.of("700"),
                service.resolveApprovers(step(NEW_MANAGER), record(NEW_MANAGER, 7000L), "100"));
    }

    @Test
    void anAccountFieldNamesTheAccount() {
        assertEquals(List.of("800"),
                service.resolveApprovers(step(REVIEWER_ACCOUNT), record(REVIEWER_ACCOUNT, 800L), "100"));
        verify(orgService, never()).getUserIdOfEmployee(any());
    }

    @Test
    void aFieldHeldAsAReferenceIsReadByItsId() {
        when(orgService.getUserIdOfEmployee(7000L)).thenReturn(700L);
        assertEquals(List.of("700"), service.resolveApprovers(step(NEW_MANAGER),
                record(NEW_MANAGER, ModelReference.of(7000L, "Ann Lee")), "100"));
    }

    /**
     * Nobody, and the reason says which: the field is empty, the employee in it has no account who can approve, or the
     * flow reads a field that no longer names a person. Nobody takes it up for the person in the field, as for the
     * people a step names: no manager of theirs, no department above.
     */
    @Test
    void nobodyWhenTheFieldNamesNobodyWhoCanApprove() {
        when(orgService.getUserIdOfEmployee(7000L)).thenReturn(null);
        when(orgService.getEmployeeNames(List.of(7000L))).thenReturn(Map.of(7000L, "Ann Lee"));

        assertTrue(service.resolveApprovers(step(NEW_MANAGER), record(NEW_MANAGER, null), "100").isEmpty());
        assertEquals("New Manager on the request is empty",
                service.whyNobody(step(NEW_MANAGER), record(NEW_MANAGER, null), "100"));

        assertTrue(service.resolveApprovers(step(NEW_MANAGER), record(NEW_MANAGER, 7000L), "100").isEmpty());
        assertEquals("Ann Lee, New Manager on the request, cannot approve",
                service.whyNobody(step(NEW_MANAGER), record(NEW_MANAGER, 7000L), "100"));
        verify(orgService, never()).getSupervisorOfEmployee(any(), anyInt());

        // A field that names no person — a project, a computed field, one removed since the flow was published.
        for (String field : List.of(PROJECT, COMPUTED_MANAGER, "removedFieldId")) {
            assertTrue(service.resolveApprovers(step(field), record(field, 7000L), "100").isEmpty(), field);
            assertEquals("the request has no field " + field + " naming a person",
                    service.whyNobody(step(field), record(field, 7000L), "100"), field);
        }
    }

    /** The field is read on the record's model: an instance without a record has none, and finds nobody by it. */
    @Test
    void anInstanceWithoutARecordFindsNobodyByAField() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(NEW_MANAGER, 7000L);

        assertTrue(service.resolveApprovers(step(NEW_MANAGER), variables, "100").isEmpty());
        verify(orgService, never()).getUserIdOfEmployee(any());
    }

    /** One person at most: a step whose only group reads a field passes on that one's approval, whatever its mode. */
    @Test
    void aFieldNamesOnePersonAtMost() {
        CompiledFlowNode node = step(NEW_MANAGER);
        ((ApprovalNodeConfig) node.getParsedConfig()).setApprovalMode(VoteThresholdMode.UNANIMOUS);
        ApprovalNodeConfig cfg = (ApprovalNodeConfig) node.getParsedConfig();

        assertFalse(cfg.mayHaveSeveralApprovers());
        assertTrue(cfg.findsApproversByRule(), "an empty field is what the empty-approver strategy is for");
        assertEquals(VoteThresholdMode.ANY_ONE, service.resolveApprovalMode(node));
    }

    /** With the new manager and the employee's supervisor as two groups, either may approve; each is found once. */
    @Test
    void aFieldGroupJoinsTheStepsPool() {
        when(orgService.getUserIdOfEmployee(7000L)).thenReturn(700L);
        when(orgService.getSupervisorOfEmployee(5000L, 1)).thenReturn(new ApproverInfo(700L, "Same person"));
        CompiledFlowNode node = CompiledFlowNode.builder().nodeId("approval-1").type(FlowNodeType.APPROVAL)
                .parsedConfig(ApprovalNodeConfig.builder().approverSources(List.of(
                        Map.of("type", "Supervisor"), Map.of("type", "RecordField", "field", NEW_MANAGER))).build())
                .build();
        Map<String, Object> variables = record(NEW_MANAGER, 7000L);
        variables.put(ApproverResolutionService.SUBJECT_ID_KEY, 5000L);

        assertEquals(List.of("700"), service.resolveApprovers(node, variables, "100"));
    }

    /** A department-based source takes its department from a field of the record: the department, never a list. */
    @Test
    void aDepartmentFieldNamesTheDepartment() {
        when(orgService.getDeptLeader(20L)).thenReturn(new ApproverInfo(310L, "Receiving leader"));
        CompiledFlowNode node = CompiledFlowNode.builder().nodeId("approval-1").type(FlowNodeType.APPROVAL)
                .parsedConfig(ApprovalNodeConfig.builder().approverSources(List.of(
                        Map.of("type", "DeptLeader", "deptScope", "VARIABLE_DEPT", "deptVariable", NEW_DEPT))).build())
                .build();

        assertEquals(List.of("310"), service.resolveApprovers(node,
                record(NEW_DEPT, ModelReference.of(20L, "Sales")), "100"));
        assertTrue(service.resolveApprovers(node, record(NEW_DEPT, List.of(20L, 30L)), "100").isEmpty());
    }

    /** A CC rule's recipients read from a field the same way. */
    @Test
    void aCcRuleReadsItsRecipientFromAField() {
        FlowExecutionState state = FlowExecutionState.builder()
                .variables(record(REVIEWER_ACCOUNT, 800L)).initiatorId("100").build();

        assertEquals(List.of("800"),
                service.resolvePeople(Map.of("type", "RecordField", "field", REVIEWER_ACCOUNT), state));
    }

    private static CompiledFlowNode step(String field) {
        return CompiledFlowNode.builder()
                .nodeId("approval-1")
                .type(FlowNodeType.APPROVAL)
                .parsedConfig(ApprovalNodeConfig.builder()
                        .approverSources(List.of(Map.of("type", "RecordField", "field", field)))
                        .build())
                .build();
    }

    /** A record's approval of the fixture's model, its {@code field} holding {@code value}. */
    private static Map<String, Object> record(String field, Object value) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(ApproverResolutionService.MODEL_KEY, MODEL);
        variables.put(field, value);
        return variables;
    }
}
