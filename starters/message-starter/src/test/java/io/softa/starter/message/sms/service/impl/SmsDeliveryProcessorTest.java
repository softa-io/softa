package io.softa.starter.message.sms.service.impl;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.support.SmsAdapterFactory;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;

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
 * Delivery-lifecycle contract for {@link SmsDeliveryProcessor}. Unlike mail,
 * the provider config is resolved <i>after</i> the CAS claim, so a
 * duplicate-delivery CAS miss must short-circuit before any provider lookup.
 */
class SmsDeliveryProcessorTest {

    private SmsProviderDispatcher dispatcher;
    private SmsAdapterFactory adapterFactory;
    private SmsProviderAdapter adapter;
    private SmsSendRecordService recordService;
    private RateLimiter rateLimiter;
    private SendFailureHandler sendFailureHandler;
    private MessageMetrics metrics;
    private SmsDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        dispatcher = mock(SmsProviderDispatcher.class);
        adapterFactory = mock(SmsAdapterFactory.class);
        adapter = mock(SmsProviderAdapter.class);
        recordService = mock(SmsSendRecordService.class);
        rateLimiter = mock(RateLimiter.class);
        sendFailureHandler = mock(SendFailureHandler.class);
        metrics = mock(MessageMetrics.class);

        processor = new SmsDeliveryProcessor();
        ReflectionTestUtils.setField(processor, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(processor, "adapterFactory", adapterFactory);
        ReflectionTestUtils.setField(processor, "recordService", recordService);
        ReflectionTestUtils.setField(processor, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(processor, "sendFailureHandler", sendFailureHandler);
        ReflectionTestUtils.setField(processor, "metrics", metrics);
    }

    private SmsSendRecord record(long id, SmsSendStatus status, long version) {
        SmsSendRecord r = new SmsSendRecord();
        r.setId(id);
        r.setStatus(status);
        r.setVersion(version);
        r.setProviderConfigId(20L);
        r.setPhoneNumber("+8613800138000");
        r.setContent("hello");
        return r;
    }

    private SmsProviderConfig config() {
        SmsProviderConfig c = new SmsProviderConfig();
        c.setId(20L);
        c.setProviderType(SmsProvider.TWILIO);
        return c;
    }

    private void claimAndResolve() {
        when(recordService.casStatus(7L, 0L, SmsSendStatus.SENDING)).thenReturn(true);
        when(dispatcher.resolveProviderById(20L)).thenReturn(config());
    }

    private void allowQuota() {
        when(rateLimiter.tryAcquire(eq("sms"), eq(20L), any(), any())).thenReturn(RateLimiter.Outcome.ALLOWED);
    }

    @Test
    void recordNotFound_isNoOp() {
        when(recordService.getById(1L)).thenReturn(Optional.empty());

        processor.process(1L);

        verify(recordService, never()).casStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(dispatcher, adapterFactory, sendFailureHandler);
    }

    @Test
    void alreadyTerminalOrSending_isIgnored() {
        when(recordService.getById(1L)).thenReturn(Optional.of(record(1L, SmsSendStatus.SENT, 2L)));

        processor.process(1L);

        verify(recordService, never()).casStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(dispatcher, adapterFactory, sendFailureHandler);
    }

    @Test
    void casMiss_isTreatedAsDuplicate_noProviderLookup() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        when(recordService.casStatus(7L, 0L, SmsSendStatus.SENDING)).thenReturn(false);

        processor.process(7L);

        verify(dispatcher, never()).resolveProviderById(anyLong());
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), anyLong(), any());
        verifyNoInteractions(adapterFactory, sendFailureHandler);
    }

    @Test
    void configNotResolvable_afterClaim_fails() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        when(recordService.casStatus(7L, 0L, SmsSendStatus.SENDING)).thenReturn(true);
        when(dispatcher.resolveProviderById(20L)).thenThrow(new RuntimeException("provider gone"));

        processor.process(7L);

        verify(sendFailureHandler).handle(eq("sms"), isNull(), eq(TopicRoute.SMS_SEND), eq("SmsSendRecord"),
                eq(7L), eq(1L), eq(0), isNull(), contains("Config not resolvable"),
                any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(adapterFactory);
    }

    @Test
    void quotaExceeded_failsWithoutCallingProvider() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        claimAndResolve();
        when(rateLimiter.tryAcquire(eq("sms"), eq(20L), any(), any()))
                .thenReturn(RateLimiter.Outcome.DAILY_EXCEEDED);

        processor.process(7L);

        verify(sendFailureHandler).handle(eq("sms"), eq("twilio"), eq(TopicRoute.SMS_SEND), eq("SmsSendRecord"),
                eq(7L), eq(1L), eq(0), eq("QUOTA_EXCEEDED"), contains("Rate limit"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(adapterFactory, never()).getAdapter(any());
    }

    @Test
    void success_marksSentWithProviderIdentityAndCountsMetric() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        claimAndResolve();
        allowQuota();
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(SmsSendResult.success("SID-1"));

        processor.process(7L);

        verify(recordService).markSent(7L, 1L, "SID-1", 20L, SmsProvider.TWILIO);
        verify(metrics).sent("sms", "twilio");
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void providerFailureResult_isHandledAsFailure() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        claimAndResolve();
        allowQuota();
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class)))
                .thenReturn(SmsSendResult.failure("REJECTED", "invalid number"));

        processor.process(7L);

        verify(sendFailureHandler).handle(eq("sms"), eq("twilio"), eq(TopicRoute.SMS_SEND), eq("SmsSendRecord"),
                eq(7L), eq(1L), eq(0), eq("REJECTED"), eq("invalid number"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void providerThrows_isHandledAsFailure() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        claimAndResolve();
        allowQuota();
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenThrow(new RuntimeException("timeout"));

        processor.process(7L);

        verify(sendFailureHandler).handle(eq("sms"), eq("twilio"), eq(TopicRoute.SMS_SEND), eq("SmsSendRecord"),
                eq(7L), eq(1L), eq(0), isNull(), eq("timeout"),
                any(SendFailureHandler.RecordTransitions.class));
    }

    @Test
    void nullProviderResult_isHandledAsFailure() {
        when(recordService.getById(7L)).thenReturn(Optional.of(record(7L, SmsSendStatus.PENDING, 0L)));
        claimAndResolve();
        allowQuota();
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(null);

        processor.process(7L);

        verify(sendFailureHandler).handle(eq("sms"), eq("twilio"), eq(TopicRoute.SMS_SEND), eq("SmsSendRecord"),
                eq(7L), eq(1L), eq(0), isNull(), eq("Provider returned failure"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), anyLong(), any());
    }
}
