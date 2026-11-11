package io.softa.starter.message.shared.retry;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.metrics.MessageMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the shared failure dispatch routes each {@link RetryDecision} arm
 * to the right side effects, independent of channel.
 */
class SendFailureHandlerTest {

    private SendFailureHandler handler;
    private ErrorClassifier errorClassifier;
    private ExponentialBackoffPolicy retryPolicy;
    private DeadLetterMessageService deadLetterMessageService;
    private MessageMetrics metrics;
    private SendFailureHandler.RecordTransitions tx;
    private SendFailureHandler.RecordTransitions.MarkRetry markRetry;
    private SendFailureHandler.RecordTransitions.MarkTerminal markFailed;
    private SendFailureHandler.RecordTransitions.MarkTerminal markDeadLetter;

    @BeforeEach
    void setUp() {
        handler = new SendFailureHandler();
        errorClassifier = mock(ErrorClassifier.class);
        retryPolicy = mock(ExponentialBackoffPolicy.class);
        deadLetterMessageService = mock(DeadLetterMessageService.class);
        metrics = mock(MessageMetrics.class);

        ReflectionTestUtils.setField(handler, "errorClassifier", errorClassifier);
        ReflectionTestUtils.setField(handler, "retryPolicy", retryPolicy);
        ReflectionTestUtils.setField(handler, "deadLetterMessageService", deadLetterMessageService);
        ReflectionTestUtils.setField(handler, "metrics", metrics);

        markRetry = mock(SendFailureHandler.RecordTransitions.MarkRetry.class);
        markFailed = mock(SendFailureHandler.RecordTransitions.MarkTerminal.class);
        markDeadLetter = mock(SendFailureHandler.RecordTransitions.MarkTerminal.class);
        tx = new SendFailureHandler.RecordTransitions(markRetry, markFailed, markDeadLetter);

        when(errorClassifier.classify(any(), any())).thenReturn(ErrorCategory.TRANSIENT);
    }

    @Test
    void retryDecisionSchedulesTheRecordAndCountsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Retry(Duration.ofSeconds(30)));
        when(markRetry.apply(eq(7L), eq(1L), eq("EC"), eq("EM"), any())).thenReturn(true);
        LocalDateTime before = LocalDateTime.now();

        handler.handle("mail", "smtp", 7L, 1L, 0, "EC", "EM", tx);

        // Due again once the back-off has passed: that is all the delivery loop needs.
        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(markRetry).apply(eq(7L), eq(1L), eq("EC"), eq("EM"), next.capture());
        assertThat(next.getValue()).isAfterOrEqualTo(before.plusSeconds(30));
        verify(metrics).failed("mail", "smtp", "retry");
        verifyNoInteractions(deadLetterMessageService);
    }

    @Test
    void retryCasMiss_skipsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Retry(Duration.ofSeconds(30)));
        when(markRetry.apply(any(), anyLong(), any(), any(), any())).thenReturn(false);

        handler.handle("mail", "smtp", 7L, 1L, 0, "EC", "EM", tx);

        verifyNoInteractions(metrics, deadLetterMessageService);
    }

    @Test
    void failDecisionMarksFailedWithoutRetryOrDeadLetter() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Fail());
        when(markFailed.apply(9L, 2L, "EC", "EM")).thenReturn(true);

        handler.handle("sms", "twilio", 9L, 2L, 1, "EC", "EM", tx);

        verify(markFailed).apply(9L, 2L, "EC", "EM");
        verify(markRetry, never()).apply(any(), anyLong(), any(), any(), any());
        verify(metrics).failed("sms", "twilio", "failed");
        verifyNoInteractions(deadLetterMessageService);
    }

    @Test
    void failCasMiss_skipsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.Fail());
        when(markFailed.apply(9L, 2L, "EC", "EM")).thenReturn(false);

        handler.handle("sms", "twilio", 9L, 2L, 1, "EC", "EM", tx);

        verifyNoInteractions(metrics, deadLetterMessageService);
    }

    @Test
    void deadLetterDecisionMarksArchivesAndCountsMetric() {
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.DeadLetter());
        when(errorClassifier.classify(any(), any())).thenReturn(ErrorCategory.PERMANENT);
        when(markDeadLetter.apply(5L, 0L, "EC", "EM")).thenReturn(true);

        handler.handle("mail", "smtp", 5L, 0L, 3, "EC", "EM", tx);

        verify(markDeadLetter).apply(5L, 0L, "EC", "EM");
        verify(deadLetterMessageService).archiveSendExhausted(
                "mail", 5L, "smtp", "EC", "EM", ErrorCategory.PERMANENT, 3);
        verify(metrics).failed("mail", "smtp", "dead_letter");
        verify(markRetry, never()).apply(any(), anyLong(), any(), any(), any());
    }

    @Test
    void deadLetterCasMiss_skipsArchiveMetricAndLog() {
        // The critical guard: a superseded version must NOT create a ghost
        // dead_letter_message row or a spurious dead_letter metric.
        when(retryPolicy.decide(anyInt(), any())).thenReturn(new RetryDecision.DeadLetter());
        when(markDeadLetter.apply(5L, 0L, "EC", "EM")).thenReturn(false);

        handler.handle("mail", "smtp", 5L, 0L, 3, "EC", "EM", tx);

        verifyNoInteractions(deadLetterMessageService, metrics);
    }
}
