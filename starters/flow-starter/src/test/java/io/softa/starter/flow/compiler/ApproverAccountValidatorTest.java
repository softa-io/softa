package io.softa.starter.flow.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.CompileDiagnostic.Severity;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.compiler.validation.ApproverAccountValidator;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.TestActorAccounts;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The users a flow names who cannot act now — static approvers, the escalation target, CC recipients — are warned
 * of, never refused: a warning does not stop a compile, an error still does.
 */
class ApproverAccountValidatorTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler(List.of(new ApproverAccountValidator(
            // Bob and the escalation target are frozen; 3002 has no account.
            TestActorAccounts.of(unusable(), Map.of("2002", "Bob Wu", "2009", "Hal Ko")))));

    private static Map<Long, String> unusable() {
        Map<Long, String> unusable = new HashMap<>();
        unusable.put(2002L, "Frozen");
        unusable.put(2009L, "Frozen");
        unusable.put(3002L, null);
        return unusable;
    }

    @Test
    void peopleWhoCannotActAreWarnedOf() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(Map.of("approvers", List.of("2001", "2002"),
                "timeout", Map.of("timeoutHours", 24, "timeoutStrategy", "Escalate", "escalateToUserId", "2009"))));

        assertEquals(List.of(
                new CompileDiagnostic(Severity.WARNING, null, "manager", "Approval", "config.approvers",
                        "UNUSABLE_APPROVER", "Approval node 'manager' names approvers who cannot act now and are "
                                + "passed over when the step is reached: Bob Wu's account is frozen"),
                new CompileDiagnostic(Severity.WARNING, null, "manager", "Approval", "config.timeout.escalateToUserId",
                        "UNUSABLE_ESCALATE_TARGET", "Approval node 'manager' escalates to someone who cannot act now, "
                                + "so an overdue step is reminded instead: Hal Ko's account is frozen"),
                new CompileDiagnostic(Severity.WARNING, null, null, null, null, "UNUSABLE_CC_RECIPIENT",
                        "CC rule 1 names recipients who cannot act now and are not CC'd: 3002 has no account")),
                diagnostics);
    }

    @Test
    void aWarningDoesNotStopTheCompileAndAnErrorStillDoes() {
        assertDoesNotThrow(() -> compiler.compile(flow(Map.of("approvers", List.of("2002")))));

        FlowCompileException refused = assertThrows(FlowCompileException.class,
                () -> compiler.compile(flow(Map.of("approvers", List.of("2002"), "approvalMode", "MinCount"))));
        assertTrue(refused.getDiagnostics().stream().anyMatch(d -> d.severity() == Severity.WARNING));
        assertEquals(refused.getDiagnostics().stream().filter(d -> d.severity() == Severity.ERROR).count()
                + " compilation error(s)", refused.getMessage(), "the warnings are not counted as errors");
    }

    @Test
    void approversWhoCanActRaiseNothing() {
        assertTrue(compiler.validate(flow(Map.of("approvers", List.of("2001")))).stream()
                .noneMatch(d -> d.code().startsWith("UNUSABLE_APPROVER")));
    }

    /** START → Manager approval (configured so) → END, CC'ing 3001 and 3002 on submit. */
    private static DesignFlowDefinition flow(Map<String, Object> config) {
        return DesignFlowDefinition.builder()
                .name("Leave")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .cc(List.of(FlowCcRule.builder().timing(CcTiming.ON_SUBMIT).recipients(List.of("3001", "3002")).build()))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                                FlowGraphNode.builder().id("manager").type(FlowNodeType.APPROVAL).label("Manager approval")
                                        .config(config).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("manager").build(),
                                FlowGraphEdge.builder().id("e2").source("manager").target("end").build()))
                        .build())
                .build();
    }
}
