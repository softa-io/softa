package io.softa.starter.message.sms.service.impl;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.shared.MonthlyQuotaGuard;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.support.SmsAdapterFactory;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.sms.support.SmsRoutingPlanner;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Delivery contract for {@link SmsDeliveryProcessor#deliver}: a claimed record is prepared once —
 * content rendered, number routed to a provider, quota charged, written back — then rate-limited and
 * sent through the provider it was routed to; anything that stops preparation lands on the record as
 * a failure with a marker code.
 */
class SmsDeliveryProcessorTest {

    private SmsProviderDispatcher dispatcher;
    private SmsTemplateService templateService;
    private SmsRoutingPlanner routingPlanner;
    private MonthlyQuotaGuard quotaGuard;
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
        templateService = mock(SmsTemplateService.class);
        routingPlanner = mock(SmsRoutingPlanner.class);
        quotaGuard = mock(MonthlyQuotaGuard.class);
        adapterFactory = mock(SmsAdapterFactory.class);
        adapter = mock(SmsProviderAdapter.class);
        recordService = mock(SmsSendRecordService.class);
        rateLimiter = mock(RateLimiter.class);
        sendFailureHandler = mock(SendFailureHandler.class);
        metrics = mock(MessageMetrics.class);

        processor = new SmsDeliveryProcessor();
        ReflectionTestUtils.setField(processor, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(processor, "templateService", templateService);
        ReflectionTestUtils.setField(processor, "routingPlanner", routingPlanner);
        ReflectionTestUtils.setField(processor, "quotaGuard", quotaGuard);
        ReflectionTestUtils.setField(processor, "adapterFactory", adapterFactory);
        ReflectionTestUtils.setField(processor, "recordService", recordService);
        ReflectionTestUtils.setField(processor, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(processor, "sendFailureHandler", sendFailureHandler);
        ReflectionTestUtils.setField(processor, "metrics", metrics);

        when(rateLimiter.tryAcquire(eq("sms"), anyLong(), any(), any())).thenReturn(RateLimiter.Outcome.ALLOWED);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(recordService.markPrepared(any(SmsSendRecord.class), anyLong())).thenReturn(true);
    }

    private static SmsProviderConfig config() {
        SmsProviderConfig c = new SmsProviderConfig();
        c.setId(20L);
        c.setProviderType(SmsProvider.TWILIO);
        return c;
    }

    // ---- a prepared record: replayed through the provider it was routed to -------------------------

    private static SmsSendRecord prepared() {
        SmsSendRecord r = new SmsSendRecord();
        r.setId(7L);
        r.setStatus(SmsSendStatus.SENDING);
        r.setRetryCount(0);
        r.setPhoneNumber("+8613800138000");
        r.setContent("hello");
        r.setProviderConfigId(20L);
        r.setProviderType(SmsProvider.TWILIO);
        return r;
    }

    @Test
    void aPreparedRecordIsSentThroughItsProvider() {
        when(dispatcher.resolveProviderById(20L)).thenReturn(config());
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(SmsSendResult.success("SID-1"));

        processor.deliver(prepared(), 3L);

        verify(recordService).markSent(eq(7L), eq(3L), eq("SID-1"), eq(20L), eq(SmsProvider.TWILIO), anyInt());
        verify(metrics).sent("sms", "twilio");
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(templateService, routingPlanner, quotaGuard, sendFailureHandler);
    }

    @Test
    void thePreparedProviderGone_failsTheRecord() {
        when(dispatcher.resolveProviderById(20L))
                .thenThrow(new BusinessException("SMS provider config with ID 20 not found."));

        processor.deliver(prepared(), 3L);

        verify(sendFailureHandler).handle(eq("sms"), isNull(), eq(7L), eq(3L), eq(0),
                eq("CONFIG_NOT_RESOLVABLE"), contains("not found"), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(adapterFactory);
    }

    @Test
    void rateLimitExceeded_isHandledWithoutSending() {
        when(dispatcher.resolveProviderById(20L)).thenReturn(config());
        when(rateLimiter.tryAcquire(eq("sms"), eq(20L), any(), any())).thenReturn(RateLimiter.Outcome.DAILY_EXCEEDED);

        processor.deliver(prepared(), 3L);

        verify(sendFailureHandler).handle(eq("sms"), eq("twilio"), eq(7L), eq(3L), eq(0),
                eq("QUOTA_EXCEEDED"), contains("Rate limit"), any(SendFailureHandler.RecordTransitions.class));
        verify(adapter, never()).send(any(), any());
    }

    @Test
    void providerFailureResult_isHandledAsFailure() {
        when(dispatcher.resolveProviderById(20L)).thenReturn(config());
        when(adapter.send(any(), any(SmsAdapterRequest.class)))
                .thenReturn(SmsSendResult.failure("REJECTED", "invalid number"));

        processor.deliver(prepared(), 3L);

        verify(sendFailureHandler).handle(eq("sms"), eq("twilio"), eq(7L), eq(3L), eq(0),
                eq("REJECTED"), eq("invalid number"), any(SendFailureHandler.RecordTransitions.class));
    }

    @Test
    void recordingSentFails_isNotASendFailure() {
        when(dispatcher.resolveProviderById(20L)).thenReturn(config());
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(SmsSendResult.success("SID-1"));
        doThrow(new RuntimeException("lock wait timeout")).when(recordService)
                .markSent(eq(7L), eq(3L), eq("SID-1"), eq(20L), eq(SmsProvider.TWILIO), anyInt());

        assertDoesNotThrow(() -> processor.deliver(prepared(), 3L));

        verifyNoInteractions(sendFailureHandler);
    }

    // ---- the first attempt: preparing the record ---------------------------------------------------

    private static SmsSendRecord fresh() {
        SmsSendRecord r = new SmsSendRecord();
        r.setId(7L);
        r.setStatus(SmsSendStatus.SENDING);
        r.setRetryCount(0);
        r.setPhoneNumber("+8613800138000");
        r.setTemplateCode("user.verification-code");
        r.setTemplateVariables(JsonUtils.objectToJsonNode(Map.of("code", "123456")));
        r.setPlatformScope(true);
        return r;
    }

    @Test
    void theFirstAttemptRendersRoutesChargesWritesBackThenSends() {
        SmsTemplate template = new SmsTemplate();
        template.setCode("user.verification-code");
        when(templateService.resolve("user.verification-code", MessageScope.PLATFORM)).thenReturn(template);
        when(templateService.renderContent(eq(template), eq(Map.of("code", "123456")))).thenReturn("Your code is 123456");
        when(routingPlanner.plan(any(SmsRoutingPlanner.RoutingRequest.class)))
                .thenReturn(new SmsRoutingPlanner.Plan(config(), "TPL-1", "SIGN", "CN", null));
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(SmsSendResult.success("SID-1"));
        SmsSendRecord record = fresh();

        processor.deliver(record, 3L);

        assertThat(record.getContent()).isEqualTo("Your code is 123456");
        assertThat(record.getProviderConfigId()).isEqualTo(20L);
        assertThat(record.getProviderType()).isEqualTo(SmsProvider.TWILIO);
        assertThat(record.getSignName()).isEqualTo("SIGN");
        assertThat(record.getExternalTemplateId()).isEqualTo("TPL-1");
        verify(routingPlanner).plan(new SmsRoutingPlanner.RoutingRequest(
                "+8613800138000", null, "user.verification-code", null, null, template));
        verify(quotaGuard).consume("sms", TenantScopes.PLATFORM);
        verify(recordService).markPrepared(record, 3L);
        verify(recordService).markSent(eq(7L), eq(4L), eq("SID-1"), eq(20L), eq(SmsProvider.TWILIO), anyInt());
    }

    @Test
    void noProviderForTheCountry_failsTheRecordWithoutChargingQuota() {
        SmsTemplate template = new SmsTemplate();
        template.setCode("user.verification-code");
        when(templateService.resolve("user.verification-code", MessageScope.PLATFORM)).thenReturn(template);
        when(templateService.renderContent(eq(template), any())).thenReturn("Your code is 123456");
        when(routingPlanner.plan(any(SmsRoutingPlanner.RoutingRequest.class)))
                .thenThrow(new BusinessException("No SMS provider configured for region CN."));

        processor.deliver(fresh(), 3L);

        verify(sendFailureHandler).handle(eq("sms"), isNull(), eq(7L), eq(3L), eq(0),
                eq("CONFIG_NOT_RESOLVABLE"), contains("No SMS provider"), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(quotaGuard, adapterFactory);
        verify(recordService, never()).markPrepared(any(), anyLong());
    }

    @Test
    void missingTemplate_failsTheRecord() {
        when(templateService.resolve("user.verification-code", MessageScope.PLATFORM))
                .thenThrow(new BusinessException("No SMS template found for code user.verification-code"));

        processor.deliver(fresh(), 3L);

        verify(sendFailureHandler).handle(eq("sms"), isNull(), eq(7L), eq(3L), eq(0),
                eq("TEMPLATE_NOT_RESOLVABLE"), contains("No SMS template"), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(routingPlanner, quotaGuard, adapterFactory);
    }

    @Test
    void quotaUsedUp_failsTheRecordWithoutSending() {
        SmsTemplate template = new SmsTemplate();
        template.setCode("user.verification-code");
        when(templateService.resolve("user.verification-code", MessageScope.PLATFORM)).thenReturn(template);
        when(templateService.renderContent(eq(template), any())).thenReturn("Your code is 123456");
        when(routingPlanner.plan(any(SmsRoutingPlanner.RoutingRequest.class)))
                .thenReturn(new SmsRoutingPlanner.Plan(config(), null, null, "CN", null));
        doThrow(new BusinessException("The monthly SMS quota is used up.")).when(quotaGuard).consume(eq("sms"), anyLong());

        processor.deliver(fresh(), 3L);

        verify(sendFailureHandler).handle(eq("sms"), isNull(), eq(7L), eq(3L), eq(0),
                eq("MONTHLY_QUOTA_EXCEEDED"), contains("quota"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(adapterFactory);
    }

    @Test
    void callerTextWinsOverTheTemplate() {
        SmsTemplate template = new SmsTemplate();
        template.setCode("user.verification-code");
        when(templateService.resolve("user.verification-code", MessageScope.PLATFORM)).thenReturn(template);
        when(routingPlanner.plan(any(SmsRoutingPlanner.RoutingRequest.class)))
                .thenReturn(new SmsRoutingPlanner.Plan(config(), null, null, "CN", null));
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(SmsSendResult.success("SID-1"));
        SmsSendRecord record = fresh();
        record.setContent("Caller text");

        processor.deliver(record, 3L);

        assertThat(record.getContent()).isEqualTo("Caller text");
        verify(templateService, never()).renderContent(any(), any());
    }
}
