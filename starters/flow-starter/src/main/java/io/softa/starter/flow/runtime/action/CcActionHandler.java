package io.softa.starter.flow.runtime.action;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowCcRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Handles an approver CC'ing people on their pending step — one or more in one action. People who already see the
 * approval from this step — its approvers, the actor among them, and anyone CC'd on it this cycle — are skipped;
 * the others get one {@code CC} entry each and are told together. Refused when nobody is left, and as a whole when
 * anyone chosen cannot act — whose account is frozen, say — naming them.
 */
@Component
public class CcActionHandler implements FlowActionHandler<FlowCcRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;

    public CcActionHandler(FlowActionContextService contextService,
                           ApprovalActorValidator actorValidator,
                           FlowAuditService auditService,
                           ApprovalNotificationService notificationService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Override
    public FlowExecutionState handle(FlowCcRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId and actorId are required to CC on a pending task");
        }
        List<String> chosen = actorValidator.normalizeCcRecipients(request.getTargetActorIds());
        if (chosen.isEmpty()) {
            throw new FlowActionValidationException("Choose at least one person to CC");
        }

        ApprovalContext ctx = contextService.loadApprovalContext(request.getInstanceId(), request.getNodeId(), "CCed");
        FlowExecutionState state = ctx.state();
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();
        FlowExecutionStatus statusBefore = ctx.statusBefore();

        actorValidator.validateCcActor(pendingApproval, request.getActorId(), node);
        actorValidator.validateCcRecipients(chosen);
        List<String> recipients = chosen.stream()
                .filter(recipient -> !pendingApproval.getApprovers().contains(recipient))
                .filter(recipient -> auditService.findCcAudit(state, node.getNodeId(), pendingApproval.getCycleNumber(),
                        recipient).isEmpty())
                .toList();
        if (recipients.isEmpty()) {
            throw new FlowActionValidationException(
                    "Nobody to CC: everyone chosen is an approver of this step or has been CC'd on it already");
        }
        for (String recipient : recipients) {
            auditService.appendCcAudit(state, pendingApproval, definition, node,
                    request.getActorId(), recipient, request.getComment(), statusBefore);
        }
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.CcSent(state, request.getNodeId(), recipients,
                request.getComment()));
        return state;
    }
}
