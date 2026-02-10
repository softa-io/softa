package io.softa.starter.metadata.event;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.framework.orm.changelog.ChangeLogHolder;
import io.softa.framework.orm.changelog.event.TransactionEvent;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.AppStartup;

/**
 * Transaction listener for local metadata changes without MQ.
 * This listener reloads the in-memory metadata cache after a transaction commits
 * if there are changes to system models.
 */
@Component
@ConditionalOnExpression("'${mq.topics.inner-broadcast.topic:}'.trim().isEmpty()")
public class LocalMetadataListener {

    @Autowired
    private AppStartup appStartup;

    /**
     * Send ChangeLog after transaction commit, and clear transaction-bound buffer after sending.
     * @param event Transaction event
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TransactionEvent event) {
        List<ChangeLog> changeLogs = ChangeLogHolder.get();
        boolean needReload = changeLogs.stream()
                .anyMatch(changeLog -> ModelConstant.SYSTEM_MODEL.contains(changeLog.getModel()));
        if (needReload) {
            appStartup.afterPropertiesSet();
        }
    }

}
