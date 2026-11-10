package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * Runs an approval flow's CC rules ({@link FlowCcRule}, part of the revision the instance runs on): as the approval
 * is submitted, approved or rejected, the rules of that timing copy their recipients in — found the way a step's
 * approvers are, past the actor's data permissions, since they are the flow's, not the actor's.
 * <p>
 * An automatic CC is recorded like a manual one: one {@code CC} entry per recipient in the approval history, with
 * no actor — no person sent it — on the node it happened at (the start node when the approval is submitted, else
 * the step whose action ended it) and that node's current cycle. The entry gives the recipient a CC task in the
 * inbox, makes them a participant of the instance — they open it and comment on it — and is what they mark as
 * read. A recipient already CC'd on the same node and cycle is not CC'd again. The recipients CC'd now are told.
 * </p>
 * <p>
 * Each timing is run where its moment happens, once: the submission's as the instance starts, the rejection's by
 * the rejection, and the approval's where the instance completes approved ({@code FlowExecutionOrchestrator}) —
 * whichever run completed it, an approval, a retry, a timer or an async task resuming it, an administrator's
 * reassignment. The entries join the instance the caller is about to save; nothing is saved here.
 * </p>
 */
@Slf4j
@Component
public class AutoCcService {

    private final FlowActionContextService contextService;

    private final FlowAuditService auditService;

    private final ApprovalNotificationService notificationService;

    private final ApproverResolutionService approverResolution;

    public AutoCcService(FlowActionContextService contextService,
                         FlowAuditService auditService,
                         ApprovalNotificationService notificationService,
                         ApproverResolutionService approverResolution) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.approverResolution = approverResolution;
    }

    /**
     * Runs the flow's CC rules of a timing on the instance, for its caller to save.
     *
     * @param state  the instance
     * @param node   where it happens — the start node, or the step whose action ended the approval — which the
     *               CC entries are recorded on
     * @param timing the timing
     */
    public void processCc(FlowExecutionState state, CompiledFlowNode node, CcTiming timing) {
        if (state == null || node == null || state.getBundleId() == null) {
            return;
        }
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        List<FlowCcRule> rules = definition.getCc() == null ? List.of()
                : definition.getCc().stream().filter(rule -> timing.equals(rule.getTiming())).toList();
        if (rules.isEmpty()) {
            return;
        }
        List<String> recipients = new ArrayList<>();
        for (FlowCcRule rule : rules) {
            try {
                for (String recipient : ContextUtils.withoutPermissionChecks(() -> approverResolution.resolvePeople(
                        rule.getRecipientSource(), state))) {
                    if (!recipients.contains(recipient)) {
                        recipients.add(recipient);
                    }
                }
            } catch (RuntimeException e) {
                log.error("Could not resolve the {} CC recipients of instance {}: {}", timing.getType(),
                        state.getInstanceId(), e.getMessage(), e);
            }
        }
        cc(state, node, recipients, timing);
    }

    /** CCs the recipients not CC'd on the node yet; whether it CC'd anyone. */
    private boolean cc(FlowExecutionState state, CompiledFlowNode node, List<String> recipients, CcTiming timing) {
        String message = "You have been CC'd on: " + (state.getTitle() != null ? state.getTitle() : state.getInstanceId());
        List<String> ccdActorIds = new ArrayList<>();
        for (String recipient : recipients) {
            if (auditService.findCcAudit(state, node.getNodeId(), recipient).isEmpty()) {
                auditService.appendAutoCcAudit(state, node, recipient, message);
                ccdActorIds.add(recipient);
            }
        }
        if (ccdActorIds.isEmpty()) {
            return false;
        }
        log.info("Auto-CC on instance {} at {}: {} recipient(s)", state.getInstanceId(), timing.getType(),
                ccdActorIds.size());
        auditService.addTrace(state, state.getFlowId(), node, FlowTraceEventType.APPROVAL_CCED,
                "Auto-CC at " + timing.getType() + " sent to " + ccdActorIds);
        notificationService.notify(new FlowNotificationEvent.CcSent(state, node.getNodeId(), ccdActorIds, message));
        return true;
    }
}
