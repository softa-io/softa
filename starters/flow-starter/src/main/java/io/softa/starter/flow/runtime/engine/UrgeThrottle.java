package io.softa.starter.flow.runtime.engine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Who may urge the steps an approval waits on, and how often, by its flow's settings. The initiator may unless
 * the flow turns that off ({@code urgeEnabled}, on when unset); an administrator may either way. A step urged —
 * by the initiator or by an administrator — is not urged again until the flow's {@code urgeIntervalMinutes}
 * (240 when unset) have passed. The interval runs per step and per pass through it: it is measured from the
 * step's last {@code URGE} entry in the approval history on the same node and cycle, so a step reached again
 * starts afresh.
 */
@Component
public class UrgeThrottle {

    /** The interval of a flow that sets none. */
    public static final int DEFAULT_INTERVAL_MINUTES = 240;

    private final FlowBundleRegistry bundleRegistry;

    private final ApprovalAuditReader auditReader;

    public UrgeThrottle(FlowBundleRegistry bundleRegistry, ApprovalAuditReader auditReader) {
        this.bundleRegistry = bundleRegistry;
        this.auditReader = auditReader;
    }

    /** Whether the flow lets the initiator urge: on unless it turned that off. */
    public static boolean initiatorMayUrge(CompiledFlowDefinition definition) {
        return definition == null || !Boolean.FALSE.equals(definition.getUrgeEnabled());
    }

    /** How long after a step is urged it may be urged again: the flow's interval, else the default. */
    public static Duration intervalOf(CompiledFlowDefinition definition) {
        Integer minutes = definition == null ? null : definition.getUrgeIntervalMinutes();
        return Duration.ofMinutes(minutes == null || minutes < 1 ? DEFAULT_INTERVAL_MINUTES : minutes);
    }

    /** The steps that may be urged now: they wait on approvers still to act, and none was urged within the interval. */
    public List<PendingApproval> urgeable(FlowExecutionState state, LocalDateTime now) {
        Duration interval = intervalOf(definitionOf(state));
        List<ApprovalActionAuditEntry> urges = urges(state);
        return waitingSteps(state).stream()
                .filter(step -> mayUrge(lastUrge(urges, step), interval, now))
                .toList();
    }

    /**
     * Whether the approval may be urged now, and if not, when it may be: the earliest time one of the
     * steps it waits on comes out of the interval. An instance not waiting for approval, or waiting on
     * nobody, may not be urged and has no such time.
     */
    public FlowUrgeStatus status(FlowExecutionState state, LocalDateTime now) {
        if (state == null || !FlowExecutionStatus.WAITING.equals(state.getStatus())) {
            return new FlowUrgeStatus(false, null);
        }
        Duration interval = intervalOf(definitionOf(state));
        List<ApprovalActionAuditEntry> urges = urges(state);
        LocalDateTime next = null;
        for (PendingApproval step : waitingSteps(state)) {
            LocalDateTime last = lastUrge(urges, step);
            if (mayUrge(last, interval, now)) {
                return new FlowUrgeStatus(true, null);
            }
            LocalDateTime again = last.plus(interval);
            if (next == null || again.isBefore(next)) {
                next = again;
            }
        }
        return new FlowUrgeStatus(false, next);
    }

    /**
     * As {@link #status(FlowExecutionState, LocalDateTime)}, for {@code requesterId}: the initiator of a flow that
     * does not let its initiator urge may not urge the approval, now or later.
     */
    public FlowUrgeStatus status(FlowExecutionState state, String requesterId, LocalDateTime now) {
        if (state != null && requesterId != null && requesterId.equals(state.getInitiatorId())
                && !initiatorMayUrge(definitionOf(state))) {
            return new FlowUrgeStatus(false, null);
        }
        return status(state, now);
    }

    /** The approvers of a step still to act: asked, and not approved yet (a rejection ends the approval). */
    public static List<String> stillToAct(PendingApproval step) {
        if (step.getApprovers() == null) {
            return List.of();
        }
        List<String> actors = new ArrayList<>();
        for (String approver : step.getApprovers()) {
            if (!contains(step.getApprovedActors(), approver) && !actors.contains(approver)) {
                actors.add(approver);
            }
        }
        return actors;
    }

    private CompiledFlowDefinition definitionOf(FlowExecutionState state) {
        return state.getBundleId() == null ? null : bundleRegistry.getByBundleId(state.getBundleId()).orElse(null);
    }

    private static boolean mayUrge(LocalDateTime lastUrge, Duration interval, LocalDateTime now) {
        return lastUrge == null || !now.isBefore(lastUrge.plus(interval));
    }

    private static List<PendingApproval> waitingSteps(FlowExecutionState state) {
        if (state.getPendingApprovals() == null) {
            return List.of();
        }
        return state.getPendingApprovals().stream()
                .filter(step -> step != null && !stillToAct(step).isEmpty())
                .toList();
    }

    private List<ApprovalActionAuditEntry> urges(FlowExecutionState state) {
        return auditReader.fullHistory(state).stream()
                .filter(entry -> entry != null && ApprovalActionType.URGE.equals(entry.getAction()))
                .toList();
    }

    /** When the step was last urged: its newest URGE entry on the same node and cycle. */
    private static LocalDateTime lastUrge(List<ApprovalActionAuditEntry> urges, PendingApproval step) {
        LocalDateTime last = null;
        for (ApprovalActionAuditEntry urge : urges) {
            if (Objects.equals(step.getNodeId(), urge.getNodeId())
                    && Objects.equals(step.getCycleNumber(), urge.getCycleNumber())
                    && urge.getEventTime() != null
                    && (last == null || urge.getEventTime().isAfter(last))) {
                last = urge.getEventTime();
            }
        }
        return last;
    }

    private static boolean contains(List<String> actors, String actor) {
        return actors != null && actors.contains(actor);
    }
}
