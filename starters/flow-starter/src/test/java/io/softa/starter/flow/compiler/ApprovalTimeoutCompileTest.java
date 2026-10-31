package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approval step's deadline and what happens at it: reminders belong to the Remind strategy, every count is
 * at least 1, and without a deadline the rest is ignored.
 */
class ApprovalTimeoutCompileTest {

    private static final Set<String> TIMEOUT_CODES = Set.of("INVALID_TIMEOUT_HOURS", "INVALID_REMIND_INTERVAL",
            "INVALID_MAX_REMINDERS", "REMINDERS_OUTSIDE_REMIND", "MISSING_ESCALATE_TARGET");

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void remindersBelongToTheRemindStrategy() {
        // Remind is the strategy when none is chosen.
        assertFalse(has(Map.of("timeoutHours", 24, "remindIntervalHours", 4, "maxRemindTimes", 2), "REMINDERS_OUTSIDE_REMIND"));
        assertFalse(has(Map.of("timeoutHours", 24, "timeoutStrategy", "Remind", "remindIntervalHours", 4),
                "REMINDERS_OUTSIDE_REMIND"));
        assertTrue(has(Map.of("timeoutHours", 24, "timeoutStrategy", "AutoApprove", "remindIntervalHours", 4),
                "REMINDERS_OUTSIDE_REMIND"));
        assertTrue(has(Map.of("timeoutHours", 24, "timeoutStrategy", "AutoReject", "maxRemindTimes", 2),
                "REMINDERS_OUTSIDE_REMIND"));
    }

    @Test
    void anIntervalAndAMostAreAtLeastOne() {
        assertTrue(has(Map.of("timeoutHours", 24, "remindIntervalHours", 0), "INVALID_REMIND_INTERVAL"));
        assertTrue(has(Map.of("timeoutHours", 24, "remindIntervalHours", 4, "maxRemindTimes", 0), "INVALID_MAX_REMINDERS"));
        // Reminders come after the deadline, so an interval longer than the deadline is fine.
        assertFalse(has(Map.of("timeoutHours", 4, "remindIntervalHours", 24), "INVALID_REMIND_INTERVAL"));
    }

    @Test
    void escalatingNeedsSomeoneToEscalateTo() {
        assertTrue(has(Map.of("timeoutHours", 24, "timeoutStrategy", "Escalate"), "MISSING_ESCALATE_TARGET"));
    }

    @Test
    void withoutADeadlineTheRestIsIgnored() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flow(Map.of("timeoutStrategy", "Escalate",
                "remindIntervalHours", 0, "maxRemindTimes", 0)));
        assertTrue(diagnostics.stream().noneMatch(d -> TIMEOUT_CODES.contains(d.code())), diagnostics.toString());
    }

    private boolean has(Map<String, Object> timeout, String code) {
        return compiler.validate(flow(timeout)).stream().anyMatch(d -> code.equals(d.code()));
    }

    private static DesignFlowDefinition flow(Map<String, Object> timeout) {
        return DesignFlowDefinition.builder()
                .name("Timeouts")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                                        .config(Map.of("approvers", List.of("manager"), "timeout", timeout)).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
