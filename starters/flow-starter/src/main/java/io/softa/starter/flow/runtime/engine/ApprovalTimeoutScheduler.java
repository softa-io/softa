package io.softa.starter.flow.runtime.engine;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * Scheduled service for handling approval task timeouts.
 * <p>
 * Periodically checks for pending approvals that have configured timeout,
 * sends reminders, and applies timeout strategies (auto-approve, auto-reject, escalate).
 * </p>
 */
@Slf4j
@Component
public class ApprovalTimeoutScheduler {

    private static final String SYSTEM_ACTOR = "__system__";

    @Autowired
    private FlowInstanceStore instanceStore;

    @Autowired
    private FlowBundleRegistry bundleRegistry;

    @Autowired
    private FlowRuntimeEngine runtimeEngine;

    @Autowired
    private ApprovalNotificationService notificationService;

    @Autowired
    private PendingApprovalFactory pendingApprovalFactory;

    /**
     * Check for timeout reminders.
     * Triggered externally via cron-starter (flow_approval_timeout_remind).
     */
    public void checkTimeoutReminders() {
        List<FlowExecutionState> waitingStates = instanceStore.listByStatus(FlowExecutionStatus.WAITING);
        if (waitingStates.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (FlowExecutionState state : waitingStates) {
            for (PendingApproval pending : state.getPendingApprovals()) {
                try {
                    processReminder(state, pending, now);
                } catch (Exception e) {
                    log.error("Error processing timeout reminder for instance {}, node {}: {}",
                            state.getInstanceId(), pending.getNodeId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Handle timed-out tasks.
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
                        handleTimeout(state, pending);
                    } catch (Exception e) {
                        log.error("Error handling timeout for instance {}, node {}: {}",
                                state.getInstanceId(), pending.getNodeId(), e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * Send a timeout reminder if conditions are met.
     */
    private void processReminder(FlowExecutionState state, PendingApproval pending, LocalDateTime now) {
        if (pending.getDueTime() == null) {
            return;
        }

        ApprovalTimeoutConfig config = resolveTimeoutConfig(state, pending);
        if (config == null || config.getRemindIntervalHours() == null || config.getRemindIntervalHours() <= 0) {
            return;
        }

        int maxRemind = config.getMaxRemindTimes() != null ? config.getMaxRemindTimes() : 3;
        int currentRemindCount = pending.getRemindCount() != null ? pending.getRemindCount() : 0;
        if (currentRemindCount >= maxRemind) {
            return;
        }

        long hoursUntilTimeout = Duration.between(now, pending.getDueTime()).toHours();
        if (hoursUntilTimeout <= 0) {
            return; // Already timed out — handled by handleTimedOutTasks
        }

        LocalDateTime lastRemind = pending.getLastRemindTime();
        boolean shouldRemind;
        if (lastRemind == null) {
            shouldRemind = hoursUntilTimeout <= config.getRemindIntervalHours();
        } else {
            long hoursSinceLastRemind = Duration.between(lastRemind, now).toHours();
            shouldRemind = hoursSinceLastRemind >= config.getRemindIntervalHours();
        }

        if (shouldRemind) {
            int newCount = currentRemindCount + 1;
            pending.setRemindCount(newCount);
            pending.setLastRemindTime(now);
            instanceStore.save(state);

            notificationService.notify(new FlowNotificationEvent.TimeoutReminded(state, pending, newCount));
            log.info("Sent timeout reminder for instance {}, node {}, remind count: {}",
                    state.getInstanceId(), pending.getNodeId(), newCount);
        }
    }

    /**
     * Handle a timed-out pending approval based on the configured strategy.
     */
    private void handleTimeout(FlowExecutionState state, PendingApproval pending) {
        ApprovalTimeoutConfig config = resolveTimeoutConfig(state, pending);
        ApprovalTimeoutStrategy strategy = (config != null && config.getTimeoutStrategy() != null)
                ? config.getTimeoutStrategy()
                : ApprovalTimeoutStrategy.REMIND;

        log.info("Handling timeout for instance {}, node {}, strategy={}",
                state.getInstanceId(), pending.getNodeId(), strategy);

        switch (strategy) {
            case AUTO_APPROVE -> handleAutoApprove(state, pending);
            case AUTO_REJECT -> handleAutoReject(state, pending);
            case ESCALATE -> handleEscalate(state, pending, config);
            case REMIND -> {
                // Just send another reminder
                int newCount = (pending.getRemindCount() != null ? pending.getRemindCount() : 0) + 1;
                pending.setRemindCount(newCount);
                pending.setLastRemindTime(LocalDateTime.now());
                instanceStore.save(state);
                notificationService.notify(new FlowNotificationEvent.TimeoutReminded(state, pending, newCount));
            }
        }
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

    private void handleEscalate(FlowExecutionState state, PendingApproval pending, ApprovalTimeoutConfig config) {
        String escalateTo = config != null ? config.getEscalateToUserId() : null;
        if (escalateTo == null || escalateTo.isBlank()) {
            log.warn("No escalation user configured for instance {}, node {}. Falling back to REMIND.",
                    state.getInstanceId(), pending.getNodeId());
            handleTimeout(state, pending);
            return;
        }

        log.info("Escalating timed-out task: instance={}, node={}, escalateTo={}",
                state.getInstanceId(), pending.getNodeId(), escalateTo);

        // Replace all current approvers with the escalation target
        pending.getApprovers().clear();
        pending.getApprovers().add(escalateTo);
        pending.setTotalApproverCount(1);
        pending.setRequiredApprovalCount(1);
        pending.setApprovedActors(List.of());
        pending.setRejectedActors(List.of());
        // Reset due time to give the escalation user time
        if (config.getTimeoutHours() != null && config.getTimeoutHours() > 0) {
            pending.setDueTime(LocalDateTime.now().plusHours(config.getTimeoutHours()));
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



