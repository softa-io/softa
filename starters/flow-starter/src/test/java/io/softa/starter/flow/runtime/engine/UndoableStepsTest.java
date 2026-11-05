package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.changelog.ChangeLogHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Undoing the steps after an approval within the transaction that keeps the approval (zingkey-hcm#2385): their
 * writes go back to the savepoint, and what they left for the commit goes with them — the callbacks they registered,
 * told they rolled back, and the change logs they added — while everything from before them stays.
 */
class UndoableStepsTest {

    private final TransactionStatus status = mock(TransactionStatus.class);
    private final Object savepoint = new Object();

    @BeforeEach
    void setUp() {
        when(status.createSavepoint()).thenReturn(savepoint);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        ChangeLogHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void undoneTheStepsLeaveNothingForTheCommitAndWhatCameBeforeStays() {
        Callback before = new Callback();
        TransactionSynchronizationManager.registerSynchronization(before);
        ChangeLogHolder.add(List.of(new ChangeLog()));

        UndoableSteps steps = UndoableSteps.begin(status);
        Callback during = new Callback();
        TransactionSynchronizationManager.registerSynchronization(during);
        ChangeLogHolder.add(List.of(new ChangeLog(), new ChangeLog()));
        steps.undo();

        verify(status).rollbackToSavepoint(savepoint);
        verify(status).releaseSavepoint(savepoint);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).containsExactly(before);
        assertThat(during.completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(before.completions).isEmpty();
        assertThat(ChangeLogHolder.get()).hasSize(1);
    }

    @Test
    void keptTheStepsStayToCommitWithTheRest() {
        UndoableSteps steps = UndoableSteps.begin(status);
        Callback during = new Callback();
        TransactionSynchronizationManager.registerSynchronization(during);
        steps.keep();

        verify(status).releaseSavepoint(savepoint);
        verify(status, never()).rollbackToSavepoint(savepoint);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).containsExactly(during);
        assertThat(during.completions).isEmpty();
    }

    @Test
    void outsideATransactionThereIsNothingToUndo() {
        UndoableSteps steps = UndoableSteps.begin(null);

        steps.undo();
        steps.keep();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private static final class Callback implements TransactionSynchronization {

        private final List<Integer> completions = new ArrayList<>();

        @Override
        public void afterCompletion(int status) {
            completions.add(status);
        }
    }
}
