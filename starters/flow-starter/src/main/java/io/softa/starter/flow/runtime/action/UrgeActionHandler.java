package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowUrgeRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles urge (催办) actions — reminds the approvers an approval waits on. Its initiator urges it, on a flow
 * that lets them ({@code urgeEnabled}), and an administrator may, authorized by the monitoring console. Either
 * way a step is urged at most once per the flow's interval ({@link UrgeThrottle}): each step urged gets its own
 * {@code URGE} entry in the approval history, which the interval is measured from, and steps still inside it
 * are left out.
 */
@Component
public class UrgeActionHandler implements FlowActionHandler<FlowUrgeRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;
    private final UrgeThrottle throttle;

    public UrgeActionHandler(FlowActionContextService contextService,
                             FlowAuditService auditService,
                             ApprovalNotificationService notificationService,
                             UrgeThrottle throttle) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.throttle = throttle;
    }

    /** The initiator urges the approval. */
    @Override
    public FlowExecutionState handle(FlowUrgeRequest request) {
        return handle(request, false);
    }

    /**
     * Urges the approval as its initiator, or — {@code administrator} — as someone the caller has already
     * authorized to administer the instance.
     */
    public FlowExecutionState handle(FlowUrgeRequest request, boolean administrator) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to urge pending approvers");
        }

        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        contextService.requireWaitingApproval(state, request.getInstanceId(), "urged");
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        if (!administrator && !request.getActorId().equals(state.getInitiatorId())) {
            throw new FlowAuthorizationException("Only the initiator can urge the approvers of flow instance: "
                    + request.getInstanceId());
        }
        if (!administrator && !UrgeThrottle.initiatorMayUrge(definition)) {
            throw new FlowActionValidationException("Flow instance does not allow its initiator to urge the approvers: "
                    + request.getInstanceId());
        }

        LocalDateTime now = LocalDateTime.now();
        List<PendingApproval> steps = throttle.urgeable(state, now);
        if (steps.isEmpty()) {
            LocalDateTime next = throttle.status(state, now).nextUrgeAt();
            throw new FlowActionValidationException(next == null
                    ? "No pending approvers to urge for instance: " + request.getInstanceId()
                    : "The pending approvers of instance " + request.getInstanceId()
                            + " were urged recently and may be urged again after " + next);
        }

        String message = StringUtils.hasText(request.getComment())
                ? request.getComment()
                : "Please process as soon as possible";

        List<String> urgedActorIds = new ArrayList<>();
        for (PendingApproval step : steps) {
            auditService.appendApprovalAudit(state, auditService.baseEntry(definition,
                            contextService.requiredNode(definition, step.getNodeId()), step,
                            state.getStatus(), state.getStatus()).toBuilder()
                    .action(ApprovalActionType.URGE)
                    .actorId(request.getActorId())
                    .comment(message)
                    .build());
            for (String actorId : UrgeThrottle.stillToAct(step)) {
                if (!urgedActorIds.contains(actorId)) {
                    urgedActorIds.add(actorId);
                }
            }
        }

        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(state.getFlowId())
                .eventType(FlowTraceEventType.FLOW_URGED)
                .eventTime(now)
                .message("Urge sent by " + request.getActorId() + " to " + urgedActorIds + ": " + message)
                .build());

        contextService.persistState(state);

        notificationService.notify(new FlowNotificationEvent.Urged(state, urgedActorIds, request.getActorId(), message));

        return state;
    }
}
