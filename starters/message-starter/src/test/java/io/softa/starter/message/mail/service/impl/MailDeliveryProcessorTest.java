package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.smtp.SmtpMailRequest;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mail.smtp.SmtpSendResult;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Delivery-lifecycle contract for {@link MailDeliveryProcessor}
 * ({@code process} → CAS claim → quota → SMTP → mark/handle): duplicate-delivery
 * idempotency, terminal-state guard, quota failure, provider success/failure,
 * and config-resolution failure. Collaborators (record CAS, SMTP transport,
 * rate limiter, failure handler) are mocked — their internals are covered by
 * their own tests.
 */
class MailDeliveryProcessorTest {

    private MailServerDispatcher dispatcher;
    private SmtpMailTransport smtpMailTransport;
    private MailSendRecordService recordService;
    private RateLimiter rateLimiter;
    private SendFailureHandler sendFailureHandler;
    private MessageMetrics metrics;
    private MailDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        dispatcher = mock(MailServerDispatcher.class);
        smtpMailTransport = mock(SmtpMailTransport.class);
        recordService = mock(MailSendRecordService.class);
        rateLimiter = mock(RateLimiter.class);
        sendFailureHandler = mock(SendFailureHandler.class);
        metrics = mock(MessageMetrics.class);

        processor = new MailDeliveryProcessor();
        ReflectionTestUtils.setField(processor, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(processor, "smtpMailTransport", smtpMailTransport);
        ReflectionTestUtils.setField(processor, "recordService", recordService);
        ReflectionTestUtils.setField(processor, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(processor, "sendFailureHandler", sendFailureHandler);
        ReflectionTestUtils.setField(processor, "metrics", metrics);
    }

    private MailSendRecord record(long id, MailSendStatus status, long version) {
        MailSendRecord r = new MailSendRecord();
        r.setId(id);
        r.setStatus(status);
        r.setVersion(version);
        r.setServerConfigId(10L);
        r.setToAddresses(List.of("alice@example.com"));
        return r;
    }

    private MailSendServerConfig config() {
        MailSendServerConfig c = new MailSendServerConfig();
        c.setId(10L);
        c.setUsername("noreply@example.com");
        return c;
    }

    private void allowQuota() {
        when(rateLimiter.tryAcquire(eq("mail"), eq(10L), any(), any())).thenReturn(RateLimiter.Outcome.ALLOWED);
    }

    @Test
    void recordNotFound_isNoOp() {
        when(recordService.getById(1L)).thenReturn(Optional.empty());

        processor.process(1L);

        verify(recordService, never()).casStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(dispatcher, smtpMailTransport, sendFailureHandler);
    }

    @Test
    void alreadyTerminalOrSending_isIgnored() {
        when(recordService.getById(1L)).thenReturn(Optional.of(record(1L, MailSendStatus.SENT, 3L)));

        processor.process(1L);

        verify(recordService, never()).casStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(dispatcher, smtpMailTransport, sendFailureHandler);
    }

    @Test
    void configNotResolvable_claimsThenFails() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenThrow(new RuntimeException("config gone"));
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(true);

        processor.process(5L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(TopicRoute.MAIL_SEND),
                eq("MailSendRecord"), eq(5L), eq(1L), eq(0), isNull(), contains("Config not resolvable"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void configNotResolvable_butClaimLost_doesNotFail() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenThrow(new RuntimeException("config gone"));
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(false);

        processor.process(5L);

        verifyNoInteractions(sendFailureHandler);
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void casMiss_isTreatedAsDuplicate_noSend() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenReturn(config());
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(false);

        processor.process(5L);

        verify(smtpMailTransport, never()).send(any(), any());
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void quotaExceeded_failsWithoutSending() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenReturn(config());
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(true);
        when(rateLimiter.tryAcquire(eq("mail"), eq(10L), any(), any()))
                .thenReturn(RateLimiter.Outcome.MINUTE_EXCEEDED);

        processor.process(5L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(TopicRoute.MAIL_SEND),
                eq("MailSendRecord"), eq(5L), eq(1L), eq(0), eq("QUOTA_EXCEEDED"), contains("Rate limit"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void success_marksSentAndCountsMetric() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenReturn(config());
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(true);
        allowQuota();
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));

        processor.process(5L);

        verify(recordService).markSent(5L, 1L, "MSG-1", SmtpMailTransport.NAME);
        verify(metrics).sent("mail", SmtpMailTransport.NAME);
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void providerFailureResult_isHandledAsFailure() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenReturn(config());
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(true);
        allowQuota();
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class)))
                .thenReturn(SmtpSendResult.failure("SMTP_550", "mailbox unavailable"));

        processor.process(5L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(TopicRoute.MAIL_SEND),
                eq("MailSendRecord"), eq(5L), eq(1L), eq(0), eq("SMTP_550"), eq("mailbox unavailable"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any());
    }

    @Test
    void transportThrows_isHandledAsFailure() {
        when(recordService.getById(5L)).thenReturn(Optional.of(record(5L, MailSendStatus.PENDING, 0L)));
        when(dispatcher.resolveSendById(10L)).thenReturn(config());
        when(recordService.casStatus(5L, 0L, MailSendStatus.SENDING)).thenReturn(true);
        allowQuota();
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class)))
                .thenThrow(new RuntimeException("connection reset"));

        processor.process(5L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(TopicRoute.MAIL_SEND),
                eq("MailSendRecord"), eq(5L), eq(1L), eq(0), isNull(), eq("connection reset"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any());
    }
}
