package io.softa.framework.orm.changelog.event;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.framework.orm.changelog.ChangeLogHolder;
import io.softa.framework.orm.changelog.message.ChangeLogProducer;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;

/**
 * Transaction listener, send ChangeLog after transaction commit.
 */
@Component
@ConditionalOnProperty(prefix = "system", name = "enable-change-log", havingValue = "true")
public class TransactionEventListener {

    @Autowired
    private ChangeLogProducer changeLogProducer;

    /**
     * Send ChangeLog after transaction commit, and clear transaction-bound buffer after sending.
     * @param event Transaction event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TransactionEvent event) {
        List<ChangeLog> changeLogs = ChangeLogHolder.get();
        changeLogProducer.sendChangeLog(changeLogs);
        ChangeLogHolder.clear();
    }

    /**
     * Clear transaction-bound buffer after transaction rollback.
     * @param event Transaction event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onRollback(TransactionEvent event) {
        ChangeLogHolder.clear();
    }
}
