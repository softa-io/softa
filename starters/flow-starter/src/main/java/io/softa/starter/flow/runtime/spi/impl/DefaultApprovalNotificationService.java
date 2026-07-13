package io.softa.starter.flow.runtime.spi.impl;

import lombok.extern.slf4j.Slf4j;

import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;

/**
 * No-op default that logs each event with its key fields. Useful for tests
 * and demos; production deployments should provide a real implementation
 * that routes to {@code message-starter} (see {@link ApprovalNotificationService}).
 * Registered via {@code FlowAutoConfiguration} as a {@code @ConditionalOnMissingBean} default.
 */
@Slf4j
public class DefaultApprovalNotificationService implements ApprovalNotificationService {

    @Override
    public void notify(FlowNotificationEvent event) {
        String summary = switch (event) {
            case FlowNotificationEvent.TaskAssigned    e -> "task assigned: node=" + e.pendingApproval().getNodeId()
                    + ", approvers=" + e.pendingApproval().getApprovers();
            case FlowNotificationEvent.TaskCompleted   e -> "task completed: node=" + e.pendingApproval().getNodeId()
                    + ", approved=" + e.approved();
            case FlowNotificationEvent.TaskTransferred e -> "task transferred: node=" + e.pendingApproval().getNodeId()
                    + ", newActor=" + e.newActorId();
            case FlowNotificationEvent.TaskDelegated   e -> "task delegated: node=" + e.pendingApproval().getNodeId()
                    + ", from=" + e.fromActorId() + ", to=" + e.toActorId();
            case FlowNotificationEvent.TimeoutReminded e -> "timeout reminder: node=" + e.pendingApproval().getNodeId()
                    + ", count=" + e.remindCount();
            case FlowNotificationEvent.FlowCompleted   e -> "flow completed: approved=" + e.approved();
            case FlowNotificationEvent.Urged           e -> "urged: urger=" + e.urgerId()
                    + ", recipients=" + e.pendingActorIds();
            case FlowNotificationEvent.CcSent          e -> "CC sent: node=" + e.nodeId()
                    + ", recipients=" + e.recipientActorIds();
        };
        log.info("[Notification] instance={}, {}",
                event.state() == null ? null : event.state().getInstanceId(),
                summary);
    }
}
