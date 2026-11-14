package io.softa.starter.flow.runtime.engine;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.changelog.ChangeLogHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;

/**
 * Work inside the caller's transaction that can be undone on its own, keeping everything before it: the steps after
 * an approval, whose failure must not undo the approval. A savepoint undoes their writes. What they arranged for the
 * commit is dropped with them — messages to send once it commits, change logs to publish and the flows those would
 * start — and each callback dropped is told it rolled back, so it lets go of what it holds. Outside a transaction
 * there is nothing to undo, and nothing is.
 */
@Slf4j
final class UndoableSteps {

    private final TransactionStatus status;

    private final Object savepoint;

    /** The callbacks registered for the commit before the steps began; the steps' own come after. */
    private final List<TransactionSynchronization> synchronizations;

    /** How many change logs the transaction held before the steps began. */
    private final int changeLogs;

    private UndoableSteps(TransactionStatus status, Object savepoint, List<TransactionSynchronization> synchronizations,
                          int changeLogs) {
        this.status = status;
        this.savepoint = savepoint;
        this.synchronizations = synchronizations;
        this.changeLogs = changeLogs;
    }

    /** Marks where the steps begin, in the transaction the caller runs in. */
    static UndoableSteps begin() {
        return begin(currentStatus());
    }

    /** Marks where the steps begin in {@code status}'s transaction; none when null. */
    static UndoableSteps begin(TransactionStatus status) {
        Object savepoint = null;
        if (status != null) {
            try {
                savepoint = status.createSavepoint();
            } catch (TransactionException e) {
                log.debug("No savepoint in this transaction: the steps cannot be undone apart from it", e);
            }
        }
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.isSynchronizationActive()
                ? TransactionSynchronizationManager.getSynchronizations() : List.of();
        List<ChangeLog> logs = ChangeLogHolder.get();
        return new UndoableSteps(savepoint == null ? null : status, savepoint, registered,
                logs == null ? 0 : logs.size());
    }

    /** The steps went through: what they did stays, to commit with the rest. */
    void keep() {
        if (savepoint != null) {
            status.releaseSavepoint(savepoint);
        }
    }

    /** The steps failed: their writes, and what they left for the commit, are undone; what came before stays. */
    void undo() {
        if (savepoint != null) {
            status.rollbackToSavepoint(savepoint);
            status.releaseSavepoint(savepoint);
        }
        List<ChangeLog> logs = ChangeLogHolder.get();
        if (logs != null && logs.size() > changeLogs) {
            logs.subList(changeLogs, logs.size()).clear();
        }
        dropLaterSynchronizations();
    }

    private void dropLaterSynchronizations() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<TransactionSynchronization> added = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(registered -> synchronizations.stream().noneMatch(kept -> kept == registered))
                .toList();
        if (added.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        synchronizations.forEach(TransactionSynchronizationManager::registerSynchronization);
        for (TransactionSynchronization dropped : added) {
            try {
                dropped.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            } catch (RuntimeException e) {
                log.warn("A callback the failed steps left for the commit did not close cleanly: {}", e.getMessage());
            }
        }
    }

    private static TransactionStatus currentStatus() {
        try {
            return TransactionAspectSupport.currentTransactionStatus();
        } catch (NoTransactionException e) {
            return null;
        }
    }
}
