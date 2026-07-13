package io.softa.starter.message.shared.retry;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.metrics.MessageMetrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the shared failure dispatch routes each {@link RetryDecision} arm
 * to the right side effects, independent of channel. The actual CAS+enqueue
 * atomicity is covered by {@code OutboxRecordWriterTest}.
 */
class SendFailureHandlerTest {

    private SendFailureHandler handler;
    private ErrorClassifier errorClassifier;
    private ExponentialBackoffPolicy retryPolicy;
    private OutboxRecordWriter outboxRecordWriter;
    private DeadLetterMessageService deadLetterMessageService;
    private MessageMetrics metrics;
    private SendFailureHandler.RecordTransitions tx;
    private SendFailureHandler.RecordTransitions.MarkTerminal markFailed;
    private SendFailureHandler.RecordTransitions.MarkTerminal markDeadLetter;

    @BeforeEach
    void setUp() {
        handler = new SendFailureHandler();
        errorClassifier = mock(ErrorClassifier.class);
        retryPolicy = mock(ExponentialBackoffPolicy.class);
        outboxRecordWriter = mock(OutboxRecordWriter.class);
        deadLetterMessageService = mock(DeadLetterMessageService.class);
        metrics = mock(MessageMetrics.class);

        ReflectionTestUtils.setField(handler, "errorClassifier", errorClassifier);
        ReflectionTestUtils.setField(handler, "retryPolicy", retryPolicy);
        ReflectionTestUtils.setField(handler, "outboxRecordWriter", outboxRecordWriter);
        ReflectionTestUtils.setField(handler, "deadLetterMessageService", deadLetterMessageService);
        ReflectionTestUtils.setField(handler, "metrics", metrics);

        SendFailureHandler.RecordTransitions.MarkRetry markRetry =
                mock(SendFailureHandler.RecordTransitions.MarkRetry.class);
        markFailed = mock(SendFailureHandler.RecordTransitions.MarkTerminal.class);
        markDeadLetter = mock(SendFailureHandler.RecordTransitions.MarkTerminal.class);
        tx = new SendFailureHandler.RecordTransitions(markRetry, markFailed, markDeadLetter);

        when(errorClassifier.classify(any(), any())).thenReturn(ErrorCategory.TRANSIENT);
    }

    @Test
    void retryDecisionEnqueuesDelayedRetryRowAndCountsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Retry(Duration.ofSeconds(30)));
        when(outboxRecordWriter.transitionAndEnqueueAt(any(), any(), any(), any(), any())).thenReturn(true);

        handler.handle("mail", "smtp", TopicRoute.MAIL_SEND, "MailSendRecord",
                7L, 1L, 0, "EC", "EM", tx);

        verify(outboxRecordWriter).transitionAndEnqueueAt(
                any(), eq(7L), eq("MailSendRecord"), eq(TopicRoute.MAIL_SEND), any(LocalDateTime.class));
        verify(metrics).failed("mail", "smtp", "retry");
        verifyNoInteractions(deadLetterMessageService);
    }

    @Test
    void retryCasMiss_skipsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Retry(Duration.ofSeconds(30)));
        when(outboxRecordWriter.transitionAndEnqueueAt(any(), any(), any(), any(), any())).thenReturn(false);

        handler.handle("mail", "smtp", TopicRoute.MAIL_SEND, "MailSendRecord",
                7L, 1L, 0, "EC", "EM", tx);

        verifyNoInteractions(metrics, deadLetterMessageService);
    }

    @Test
    void failDecisionMarksFailedWithoutEnqueueOrDeadLetter() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Fail());
        when(markFailed.apply(9L, 2L, "EC", "EM")).thenReturn(true);

        handler.handle("sms", "twilio", TopicRoute.SMS_SEND, "SmsSendRecord",
                9L, 2L, 1, "EC", "EM", tx);

        verify(markFailed).apply(9L, 2L, "EC", "EM");
        verify(outboxRecordWriter, never()).transitionAndEnqueueAt(any(), any(), any(), any(), any());
        verify(metrics).failed("sms", "twilio", "failed");
        verifyNoInteractions(deadLetterMessageService);
    }

    @Test
    void failCasMiss_skipsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Fail());
        when(markFailed.apply(9L, 2L, "EC", "EM")).thenReturn(false);

        handler.handle("sms", "twilio", TopicRoute.SMS_SEND, "SmsSendRecord",
                9L, 2L, 1, "EC", "EM", tx);

        verifyNoInteractions(metrics, deadLetterMessageService);
    }

    @Test
    void deadLetterDecisionMarksArchivesAndCountsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.DeadLetter());
        when(errorClassifier.classify(any(), any())).thenReturn(ErrorCategory.PERMANENT);
        when(markDeadLetter.apply(5L, 0L, "EC", "EM")).thenReturn(true);

        handler.handle("mail", "smtp", TopicRoute.MAIL_SEND, "MailSendRecord",
                5L, 0L, 3, "EC", "EM", tx);

        verify(markDeadLetter).apply(5L, 0L, "EC", "EM");
        verify(deadLetterMessageService).archiveSendExhausted(
                "mail", 5L, "smtp", "EC", "EM", ErrorCategory.PERMANENT, 3);
        verify(metrics).failed("mail", "smtp", "dead_letter");
        verify(outboxRecordWriter, never()).transitionAndEnqueueAt(any(), any(), any(), any(), any());
    }

    @Test
    void deadLetterCasMiss_skipsArchiveMetricAndLog() {
        // The critical guard: a superseded version must NOT create a ghost
        // dead_letter_message row or a spurious dead_letter metric.
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.DeadLetter());
        when(markDeadLetter.apply(5L, 0L, "EC", "EM")).thenReturn(false);

        handler.handle("mail", "smtp", TopicRoute.MAIL_SEND, "MailSendRecord",
                5L, 0L, 3, "EC", "EM", tx);

        verifyNoInteractions(deadLetterMessageService, metrics);
    }
}
