package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.ApprovalTimeoutStrategy;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;

/**
 * Handles the approval steps past their deadline ({@code timeout.timeoutHours} on the approval node), by the
 * node's timeout strategy: Remind reminds the approvers still to act at the deadline, then every
 * {@code remindIntervalHours}, at most {@code maxRemindTimes} times (3 unless set) — with no interval, once;
 * AutoApprove and AutoReject decide for them; Escalate hands the step to {@code escalateToUserId} with a new
 * deadline — or, while that user cannot act ({@link ActorAccounts}), reminds as Remind does. Nothing is sent before
 * the deadline.
 */
@Slf4j
@Component
public class ApprovalTimeoutScheduler {

    /** The most reminders a Remind step gets when its node sets an interval but no most. */
    static final int DEFAULT_MAX_REMINDERS = 3;

    private static final String SYSTEM_ACTOR = "__system__";

    private final FlowInstanceStore instanceStore;
    private final FlowBundleRegistry bundleRegistry;
    private final FlowRuntimeEngine runtimeEngine;
    private final ApprovalNotificationService notificationService;
    private final PendingApprovalFactory pendingApprovalFactory;

    @Autowired(required = false)
    private ActorAccounts actorAccounts;

    public ApprovalTimeoutScheduler(FlowInstanceStore instanceStore, FlowBundleRegistry bundleRegistry,
                                    FlowRuntimeEngine runtimeEngine, ApprovalNotificationService notificationService,
                                    PendingApprovalFactory pendingApprovalFactory) {
        this.instanceStore = instanceStore;
        this.bundleRegistry = bundleRegistry;
        this.runtimeEngine = runtimeEngine;
        this.notificationService = notificationService;
        this.pendingApprovalFactory = pendingApprovalFactory;
    }

    /**
     * Handle the steps past their deadline.
     * Triggered externally via cron-starter (flow_approval_timeout_handle).
     */
    public void handleTimedOutTasks() {
        List<FlowExecutionState> waitingStates = instanceStore.listByStatus(FlowExecutionStatus.WAITING);
        if (waitingStates.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (FlowExecutionState state : waitingStates) {
            for (PendingApproval pending : List.copyOf(state.getPendingApprovals())) {
                if (pending.getDueTime() != null && pending.getDueTime().isBefore(now)) {
                    try {
                        handleTimeout(state, pending, now);
                    } catch (Exception e) {
                        log.error("Error handling timeout for instance {}, node {}: {}",
                                state.getInstanceId(), pending.getNodeId(), e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * Whether a Remind step past its deadline is reminded now: first at the deadline; then, with an interval, once
     * per interval since the last reminder until the most ({@code maxRemindTimes}, 3 unless set) are sent; with no
     * interval, never again.
     */
    static boolean remindsNow(PendingApproval pending, ApprovalTimeoutConfig config, LocalDateTime now) {
        int sent = pending.getRemindCount() == null ? 0 : pending.getRemindCount();
        Integer interval = config == null ? null : config.getRemindIntervalHours();
        if (interval == null || interval < 1) {
            return sent == 0;
        }
        int most = config.getMaxRemindTimes() == null ? DEFAULT_MAX_REMINDERS : config.getMaxRemindTimes();
        LocalDateTime last = pending.getLastRemindTime();
        return sent < most && (last == null || !now.isBefore(last.plusHours(interval)));
    }

    /**
     * Handle a pending approval past its deadline by the configured strategy.
     */
    private void handleTimeout(FlowExecutionState state, PendingApproval pending, LocalDateTime now) {
        ApprovalTimeoutConfig config = resolveTimeoutConfig(state, pending);
        ApprovalTimeoutStrategy strategy = (config != null && config.getTimeoutStrategy() != null)
                ? config.getTimeoutStrategy()
                : ApprovalTimeoutStrategy.REMIND;

        switch (strategy) {
            case AUTO_APPROVE -> handleAutoApprove(state, pending);
            case AUTO_REJECT -> handleAutoReject(state, pending);
            case ESCALATE -> handleEscalate(state, pending, config, now);
            case REMIND -> remindOverdue(state, pending, config, now);
        }
    }

    /** The Remind strategy: the approvers still to act are reminded when {@link #remindsNow} says so. */
    private void remindOverdue(FlowExecutionState state, PendingApproval pending, ApprovalTimeoutConfig config,
                               LocalDateTime now) {
        if (!remindsNow(pending, config, now)) {
            return;
        }
        int count = (pending.getRemindCount() == null ? 0 : pending.getRemindCount()) + 1;
        pending.setRemindCount(count);
        pending.setLastRemindTime(now);
        instanceStore.save(state);
        notificationService.notify(new FlowNotificationEvent.TimeoutReminded(state, pending, count));
        log.info("Sent overdue reminder {} for instance {}, node {}", count, state.getInstanceId(), pending.getNodeId());
    }

    private void handleAutoApprove(FlowExecutionState state, PendingApproval pending) {
        log.info("Auto-approving timed-out task: instance={}, node={}", state.getInstanceId(), pending.getNodeId());
        // Approve for each remaining approver
        List<String> approvers = pending.getApprovers();
        if (approvers != null) {
            for (String approver : approvers) {
                if (pending.getApprovedActors() != null && pending.getApprovedActors().contains(approver)) {
                    continue;
                }
                try {
                    FlowApproveRequest request = new FlowApproveRequest();
                    request.setInstanceId(state.getInstanceId());
                    request.setNodeId(pending.getNodeId());
                    request.setActorId(approver);
                    request.setComment("System auto-approved (timeout)");
                    runtimeEngine.approve(request);
                    return; // After first approval, re-evaluate on next cycle
                } catch (Exception e) {
                    log.warn("Failed to auto-approve for actor {} on instance {}: {}",
                            approver, state.getInstanceId(), e.getMessage());
                }
            }
        }
    }

    private void handleAutoReject(FlowExecutionState state, PendingApproval pending) {
        log.info("Auto-rejecting timed-out task: instance={}, node={}", state.getInstanceId(), pending.getNodeId());
        List<String> approvers = pending.getApprovers();
        String actor = (approvers != null && !approvers.isEmpty()) ? approvers.getFirst() : SYSTEM_ACTOR;
        try {
            FlowRejectRequest request = new FlowRejectRequest();
            request.setInstanceId(state.getInstanceId());
            request.setNodeId(pending.getNodeId());
            request.setActorId(actor);
            request.setComment("System auto-rejected (timeout)");
            runtimeEngine.reject(request);
        } catch (Exception e) {
            log.warn("Failed to auto-reject instance {}: {}", state.getInstanceId(), e.getMessage());
        }
    }

    private void handleEscalate(FlowExecutionState state, PendingApproval pending, ApprovalTimeoutConfig config,
                                LocalDateTime now) {
        String escalateTo = config != null ? config.getEscalateToUserId() : null;
        if (escalateTo == null || escalateTo.isBlank()) {
            log.warn("No escalation user configured for instance {}, node {}. Falling back to REMIND.",
                    state.getInstanceId(), pending.getNodeId());
            remindOverdue(state, pending, config, now);
            return;
        }
        if (actorAccounts != null && actorAccounts.usable(List.of(escalateTo)).isEmpty()) {
            // Dropped like any approver who cannot act: the step stays with its approvers, reminded instead.
            log.warn("Escalation user {} of instance {}, node {} cannot act. Falling back to REMIND.",
                    escalateTo, state.getInstanceId(), pending.getNodeId());
            remindOverdue(state, pending, config, now);
            return;
        }

        log.info("Escalating timed-out task: instance={}, node={}, escalateTo={}",
                state.getInstanceId(), pending.getNodeId(), escalateTo);

        // Replace all current approvers with the escalation target — a new list: the step's own may be one that
        // cannot be changed in place.
        pending.setApprovers(new ArrayList<>(List.of(escalateTo)));
        pending.setTotalApproverCount(1);
        pending.setRequiredApprovalCount(1);
        // One approver left: one rejection decides it too, or a step asking two would wait for ever on them.
        pending.setRequiredRejectCount(1);
        pending.setApprovedActors(List.of());
        pending.setRejectedActors(List.of());
        // Reset due time to give the escalation user time
        if (config.getTimeoutHours() != null && config.getTimeoutHours() > 0) {
            pending.setDueTime(now.plusHours(config.getTimeoutHours()));
        }
        pending.setRemindCount(0);
        pending.setLastRemindTime(null);

        instanceStore.save(state);
        notificationService.notify(new FlowNotificationEvent.TaskTransferred(state, pending, escalateTo));
    }

    /**
     * Resolve timeout config for a pending approval by looking up the compiled node.
     */
    private ApprovalTimeoutConfig resolveTimeoutConfig(FlowExecutionState state, PendingApproval pending) {
        Long bundleId = pending.getBundleId() != null ? pending.getBundleId() : state.getBundleId();
        Optional<CompiledFlowDefinition> defOpt = bundleId != null
                ? bundleRegistry.getByBundleId(bundleId)
                : Optional.empty();
        if (defOpt.isEmpty()) {
            return null;
        }
        CompiledFlowNode node = defOpt.get().getNodeIndex().get(pending.getNodeId());
        if (node == null) {
            return null;
        }
        return pendingApprovalFactory.resolveTimeoutConfig(node);
    }
}
