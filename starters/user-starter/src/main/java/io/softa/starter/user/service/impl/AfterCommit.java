package io.softa.starter.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A side effect that follows a change and must not be undone with it, nor undo it: run once the
 * current transaction commits (at once outside one), and a failure logged rather than thrown — the
 * change took effect, and its caller has been or will be told so.
 */
@Slf4j
final class AfterCommit {

    private AfterCommit() {
    }

    static void run(String what, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(what, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(what, action);
            }
        });
    }

    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("{} failed after the change committed: {}", what, e.getMessage());
        }
    }
}
