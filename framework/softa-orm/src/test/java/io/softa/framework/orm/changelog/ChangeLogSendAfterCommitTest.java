package io.softa.framework.orm.changelog;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.orm.changelog.message.ChangeLogProducer;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The change logs of a transaction reach MQ after it commits, every one of them, and the buffer is
 * gone once it completes. Sending and clearing used to be two transactional listeners that Spring
 * runs in the same step with equal order, so a process whose reflection listed the clear first
 * sent an empty buffer for its whole lifetime; they are now one synchronization, sending before
 * clearing.
 */
class ChangeLogSendAfterCommitTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 12, 0);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChangeLogProducer producer = mock(ChangeLogProducer.class);
    private final TransactionTemplate transaction = new TransactionTemplate(new NoResourceTransactionManager());

    @AfterEach
    void tearDown() {
        ChangeLogHolder.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyLogOfACommittedTransactionIsSentAndTheBufferCleared() {
        try (MockedStatic<ModelManager> models = stubCustomer()) {
            ChangeLogPublisherImpl publisher = publisher();
            transaction.executeWithoutResult(_ -> {
                publisher.publishCreationLog("Customer", List.of(row(1L)), NOW);
                publisher.publishCreationLog("Customer", List.of(row(2L), row(3L)), NOW);
            });

            ArgumentCaptor<List<ChangeLog>> sent = ArgumentCaptor.forClass(List.class);
            verify(producer, times(1)).sendChangeLog(sent.capture());
            assertThat(sent.getValue()).extracting(ChangeLog::getRowId).containsExactly("1", "2", "3");
            verify(events, times(1)).publishEvent(any(Object.class));
            assertThat(ChangeLogHolder.get()).isNull();
        }
    }

    @Test
    void aClearRegisteredAheadOfTheSendNoLongerEmptiesIt() {
        try (MockedStatic<ModelManager> models = stubCustomer()) {
            ChangeLogPublisherImpl publisher = publisher();
            AtomicReference<Integer> sentSize = new AtomicReference<>();
            doAnswer(call -> {
                List<?> logs = call.getArgument(0);
                sentSize.set(logs == null ? 0 : logs.size());
                return null;
            }).when(producer).sendChangeLog(any());

            transaction.executeWithoutResult(_ -> {
                // What the removed AFTER_COMPLETION listener did, registered first so it would run first
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        ChangeLogHolder.clear();
                    }
                });
                publisher.publishCreationLog("Customer", List.of(row(1L)), NOW);
            });

            assertThat(sentSize.get()).isEqualTo(1);
        }
    }

    @Test
    void aRolledBackTransactionSendsNothingAndClearsTheBuffer() {
        try (MockedStatic<ModelManager> models = stubCustomer()) {
            ChangeLogPublisherImpl publisher = publisher();
            transaction.executeWithoutResult(status -> {
                publisher.publishCreationLog("Customer", List.of(row(1L)), NOW);
                status.setRollbackOnly();
            });

            verify(producer, never()).sendChangeLog(any());
            assertThat(ChangeLogHolder.get()).isNull();
        }
    }

    private ChangeLogPublisherImpl publisher() {
        ChangeLogPublisherImpl publisher = new ChangeLogPublisherImpl();
        ReflectionTestUtils.setField(publisher, "applicationEventPublisher", events);
        ReflectionTestUtils.setField(publisher, "changeLogProducer", producer);
        return publisher;
    }

    private static Map<String, Object> row(Long id) {
        Map<String, Object> row = new HashMap<>();
        row.put(ModelConstant.ID, id);
        return row;
    }

    private static MockedStatic<ModelManager> stubCustomer() {
        MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
        models.when(() -> ModelManager.isAuditLogEnabled("Customer")).thenReturn(true);
        models.when(() -> ModelManager.getModelPrimaryKey("Customer")).thenReturn(ModelConstant.ID);
        models.when(() -> ModelManager.getModel("Customer")).thenReturn(new MetaModel());
        models.when(() -> ModelManager.getModelFields("Customer")).thenReturn(List.of());
        return models;
    }

    /** A transaction manager with no resource: Spring's commit and rollback sequence only. */
    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
