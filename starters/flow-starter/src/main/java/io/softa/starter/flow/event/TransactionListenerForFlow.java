package io.softa.starter.flow.event;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.changelog.ChangeLogHolder;
import io.softa.framework.orm.changelog.event.TransactionEvent;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;

/**
 * Transaction listener for flows.
 * <p>
 * Before the transaction is committed, triggers matching flows
 * synchronously using the ChangeLog data accumulated during the transaction.
 * This enables data validation flows that can cause transaction rollback.
 * </p>
 */
@Slf4j
@Component
public class TransactionListenerForFlow {

    @Autowired
    private FlowAutomationService automationService;

    /**
     * Before the transaction is committed, check ChangeLog and trigger sync flows.
     *
     * @param event Transaction event
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void beforeCommit(TransactionEvent event) {
        Context context = ContextHolder.getContext();
        if (context == null || !context.isTriggerFlow()) {
            return;
        }

        List<ChangeLog> changeLogs = ChangeLogHolder.get();
        if (changeLogs == null || changeLogs.isEmpty()) {
            return;
        }

        String actorId = context.getUserId() != null ? context.getUserId().toString() : null;
        List<FlowTriggerEvent> events = ChangeLogTriggerMapper.mapChangeLogs(changeLogs, actorId);

        for (FlowTriggerEvent triggerEvent : events) {
            automationService.fireSyncOnly(triggerEvent);
        }

        if (!events.isEmpty()) {
            log.debug("Triggered {} sync flows before transaction commit", events.size());
        }
    }
}
