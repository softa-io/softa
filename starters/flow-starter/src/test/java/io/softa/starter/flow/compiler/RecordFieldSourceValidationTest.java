package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.context.PersonFieldsFixture;

import static io.softa.starter.flow.runtime.context.PersonFieldsFixture.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source that reads a field of the record names one that holds what it reads, or the flow is not published: a
 * {@code RecordField} group a field of the record's own onto an employee or a login account, a {@code VARIABLE_DEPT}
 * scope one onto a department — in an approval step and in a CC rule alike.
 */
class RecordFieldSourceValidationTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        models = PersonFieldsFixture.mockModelManager();
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void aPersonFieldOfTheRecordIsPublished() {
        for (String field : List.of(EMPLOYEE, NEW_MANAGER, REVIEWER_ACCOUNT)) {
            assertDoesNotThrow(() -> compiler.compile(flow(MODEL, recordField(field), List.of())), field);
        }
    }

    @Test
    void aRecordFieldGroupNamesItsField() {
        CompileDiagnostic missing = only(MODEL, Map.of("type", "RecordField"), "MISSING_RECORD_FIELD");

        assertEquals("config.approverSources.0.field", missing.field());
    }

    /**
     * Neither a department, a project, a plain field nor a field the record does not have names a person; a computed
     * one onto an employee does, but holds nothing when the step is reached — said so.
     */
    @Test
    void aFieldThatNamesNoPersonIsRefused() {
        for (String field : List.of(NEW_DEPT, PROJECT, REASON, "noSuchField")) {
            CompileDiagnostic invalid = only(MODEL, recordField(field), "INVALID_RECORD_FIELD");
            assertEquals("config.approverSources.0.field", invalid.field(), field);
            assertEquals("Approval node 'approval' RecordField reads field '" + field + "', which is not a field of "
                    + MODEL + " pointing at an employee or a user account", invalid.message(), field);
        }
        CompileDiagnostic computed = only(MODEL, recordField(COMPUTED_MANAGER), "INVALID_RECORD_FIELD");
        assertTrue(computed.message().endsWith("which its approvals do not carry: a computed (dynamic) or sensitive field"),
                computed.message());
    }

    @Test
    void aFlowThatApprovesNoModelHasNoFieldToRead() {
        CompileDiagnostic invalid = only(null, recordField(NEW_MANAGER), "INVALID_RECORD_FIELD");

        assertTrue(invalid.message().endsWith("but the flow approves no model's records"), invalid.message());
    }

    /** The department a department-based source works on, taken from the record: a field of it onto a department. */
    @Test
    void aDepartmentScopeReadsADepartmentFieldOfTheRecord() {
        assertDoesNotThrow(() -> compiler.compile(flow(MODEL, deptLeaderOf(NEW_DEPT), List.of())));

        for (String field : List.of(NEW_MANAGER, PROJECT, "toDeptId")) {
            CompileDiagnostic invalid = only(MODEL, deptLeaderOf(field), "INVALID_DEPT_VARIABLE");
            assertEquals("config.approverSources.0.deptVariable", invalid.field(), field);
            assertTrue(invalid.message().endsWith("pointing at a department"), invalid.message());
        }
    }

    @Test
    void aCcRuleReadsAFieldTheSameWay() {
        assertDoesNotThrow(() -> compiler.compile(flow(MODEL, recordField(NEW_MANAGER), List.of(
                cc(recordField(REVIEWER_ACCOUNT)), cc(deptLeaderOf(NEW_DEPT))))));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(flow(MODEL,
                recordField(NEW_MANAGER), List.of(cc(recordField(PROJECT)), cc(Map.of("type", "RecordField")),
                        cc(deptLeaderOf(NEW_MANAGER)), cc(Map.of("type", "DeptHrbp", "deptScope", "VARIABLE_DEPT"))))));

        List<String> messages = ex.getDiagnostics().stream().filter(d -> "INVALID_CC_RULE".equals(d.code()))
                .map(CompileDiagnostic::message).toList();
        assertEquals(List.of(
                "CC rule 1 reads field 'projectId', which is not a field of TransferRequest pointing at an employee "
                        + "or a user account",
                "CC rule 2 names no field of the record to read its recipient from",
                "CC rule 3 reads field 'newManagerId', which is not a field of TransferRequest pointing at a department",
                "CC rule 4 names no field of the record to take its department from"), messages);
    }

    /** The one diagnostic of {@code code} compiling a flow of {@code model} whose step's one group is {@code group}. */
    private CompileDiagnostic only(String model, Map<String, Object> group, String code) {
        FlowCompileException ex = assertThrows(FlowCompileException.class,
                () -> compiler.compile(flow(model, group, List.of())), group.toString());
        List<CompileDiagnostic> found = ex.getDiagnostics().stream().filter(d -> code.equals(d.code())).toList();
        assertEquals(1, found.size(), ex.getDiagnostics().toString());
        return found.getFirst();
    }

    private static Map<String, Object> recordField(String field) {
        return Map.of("type", "RecordField", "field", field);
    }

    private static Map<String, Object> deptLeaderOf(String field) {
        return Map.of("type", "DeptLeader", "deptScope", "VARIABLE_DEPT", "deptVariable", field);
    }

    private static FlowCcRule cc(Map<String, Object> source) {
        return FlowCcRule.builder().timing(CcTiming.ON_APPROVED).recipientSource(source).build();
    }

    private static DesignFlowDefinition flow(String model, Map<String, Object> group, List<FlowCcRule> cc) {
        return DesignFlowDefinition.builder()
                .name("Record field approvers")
                .scenario(FlowScenario.APPROVAL)
                .trigger(model == null ? null : new SubmitTrigger(model))
                .cc(cc)
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of("approverSources", List.of(group))).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
