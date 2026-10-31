package io.softa.starter.flow.runtime.spi.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.engine.FlowStateChangeListener;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.message.inbox.service.InboxNotificationService;

/**
 * Once an approver has acted on an approval — approved, rejected, returned, passed it on, read a CC — the inbox
 * messages that told them of it (the pending approval or the CC, an urge, a timeout reminder) are old news: their
 * unread messages about the instance are marked read, after the action commits. The bell then counts what is still
 * news to them; My Tasks stays the work list.
 *
 * <p>Best effort: a message that cannot be marked is logged without touching the action.
 */
@Slf4j
public class InboxReadMarker implements FlowStateChangeListener {

    private static final String INSTANCE_MODEL = FlowInstance.class.getSimpleName();

    /** What settles a person's part in an approval. An urge, a comment or a CC sent does not. */
    private static final Set<ApprovalActionType> SETTLING = EnumSet.of(ApprovalActionType.APPROVE,
            ApprovalActionType.REJECT, ApprovalActionType.RETURN, ApprovalActionType.TRANSFER,
            ApprovalActionType.DELEGATE, ApprovalActionType.ADD_SIGN, ApprovalActionType.READ);

    private final ObjectProvider<InboxNotificationService> inbox;

    private final TransactionTemplate newTransaction;

    public InboxReadMarker(ObjectProvider<InboxNotificationService> inbox, PlatformTransactionManager transactionManager) {
        this.inbox = inbox;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void onStateChanged(FlowExecutionState state) {
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        if (delta == null || delta.isEmpty() || state.getInstanceId() == null) {
            return;
        }
        // The delta holds what this action recorded (the state is loaded afresh for each action); the ledger has
        // already written it by now, so the watermark says nothing here. Marking again is harmless.
        List<Long> actors = delta.stream()
                .filter(entry -> SETTLING.contains(entry.getAction()))
                .map(entry -> toLong(entry.getActorId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (actors.isEmpty()) {
            return;
        }
        String instanceId = state.getInstanceId();
        Context context = ContextHolder.cloneContext();
        Runnable mark = () -> ContextHolder.runWith(context, () -> ContextUtils.withoutPermissionChecks(() -> {
            InboxNotificationService notifications = inbox.getIfAvailable();
            if (notifications == null) {
                return;
            }
            // A transaction of its own: after commit the committed transaction is still bound to the thread.
            for (Long actor : actors) {
                try {
                    newTransaction.executeWithoutResult(
                            status -> notifications.markReadBySource(actor, INSTANCE_MODEL, instanceId));
                } catch (RuntimeException e) {
                    log.warn("Could not mark {}'s messages about instance {} read: {}", actor, instanceId, e.getMessage());
                }
            }
        }));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mark.run();
                }
            });
        } else {
            mark.run();
        }
    }

    private static Long toLong(String actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            return Long.valueOf(actorId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
