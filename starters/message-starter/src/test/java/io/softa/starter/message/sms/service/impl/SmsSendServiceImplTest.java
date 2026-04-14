package io.softa.starter.message.sms.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.sms.dto.BatchSmsItemDTO;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.message.SmsRetryProducer;
import io.softa.starter.message.sms.message.SmsSendMessage;
import io.softa.starter.message.sms.message.SmsSendProducer;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.support.SmsAdapterFactory;
import io.softa.starter.message.sms.support.SmsFailoverExecutor;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmsSendServiceImplTest {

    private SmsSendServiceImpl service;
    private SmsProviderDispatcher dispatcher;
    private SmsAdapterFactory adapterFactory;
    private SmsSendRecordService recordService;
    private SmsTemplateService templateService;
    private SmsTemplateProviderBindingService bindingService;
    private SmsRetryProducer retryProducer;
    private SmsSendProducer sendProducer;
    private SmsProviderAdapter adapter;
    private SmsFailoverExecutor failoverExecutor;

    @BeforeEach
    void setUp() {
        service = new SmsSendServiceImpl();
        dispatcher = mock(SmsProviderDispatcher.class);
        adapterFactory = mock(SmsAdapterFactory.class);
        recordService = mock(SmsSendRecordService.class);
        templateService = mock(SmsTemplateService.class);
        bindingService = mock(SmsTemplateProviderBindingService.class);
        retryProducer = mock(SmsRetryProducer.class);
        sendProducer = mock(SmsSendProducer.class);
        adapter = mock(SmsProviderAdapter.class);

        failoverExecutor = new SmsFailoverExecutor();
        ReflectionTestUtils.setField(failoverExecutor, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(failoverExecutor, "adapterFactory", adapterFactory);

        ReflectionTestUtils.setField(service, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(service, "adapterFactory", adapterFactory);
        ReflectionTestUtils.setField(service, "recordService", recordService);
        ReflectionTestUtils.setField(service, "templateService", templateService);
        ReflectionTestUtils.setField(service, "bindingService", bindingService);
        ReflectionTestUtils.setField(service, "retryProducer", retryProducer);
        ReflectionTestUtils.setField(service, "sendProducer", sendProducer);
        ReflectionTestUtils.setField(service, "failoverExecutor", failoverExecutor);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.TWILIO);
        config.setApiKey("ACtest");
        config.setApiSecret("token123");
        config.setSenderNumber("+15551234567");
        config.setIsEnabled(true);
        return config;
    }

    private SmsSendResult successResult() {
        SmsSendResult result = new SmsSendResult();
        result.setSuccess(true);
        result.setProviderMessageId("SM123456");
        return result;
    }

    private SmsSendResult failureResult() {
        SmsSendResult result = new SmsSendResult();
        result.setSuccess(false);
        result.setErrorCode("21211");
        result.setErrorMessage("Invalid phone number");
        return result;
    }

    // ========== Record Building Tests ==========

    @Test
    void buildRecordInitialStatusIsPending() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        final SmsSendStatus[] statusAtCreation = new SmsSendStatus[1];
        final int[] retryCountAtCreation = new int[1];
        doAnswer(invocation -> {
            SmsSendRecord r = invocation.getArgument(0);
            statusAtCreation[0] = r.getStatus();
            retryCountAtCreation[0] = r.getRetryCount();
            return null;
        }).when(recordService).createOne(any());

        service.sendNow("+8613800138000", "Hello");

        Assertions.assertEquals(SmsSendStatus.PENDING, statusAtCreation[0]);
        Assertions.assertEquals(0, retryCountAtCreation[0]);
    }

    @Test
    void buildRecordPopulatesContentPreview() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        String longContent = "A".repeat(300);
        service.sendNow("+8613800138000", longContent);

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        SmsSendRecord record = captor.getValue();
        Assertions.assertEquals(longContent, record.getContent());
        Assertions.assertEquals(200, record.getContentPreview().length());
    }

    @Test
    void buildRecordSetsDeliveryStatusUnknown() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals(SmsDeliveryStatus.UNKNOWN, captor.getValue().getDeliveryStatus());
    }

    @Test
    void buildRecordSetsProviderType() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals(SmsProvider.TWILIO, captor.getValue().getProviderType());
    }

    // ========== Send Success ==========

    @Test
    void sendSuccessUpdatesStatusToSent() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        SmsSendRecord updated = updateCaptor.getValue();
        Assertions.assertEquals(SmsSendStatus.SENT, updated.getStatus());
        Assertions.assertNotNull(updated.getSentAt());
        Assertions.assertEquals("SM123456", updated.getProviderMessageId());
    }

    @Test
    void sendUsesExplicitProviderConfigId() {
        SmsProviderConfig config = createConfig();
        config.setId(42L);
        when(dispatcher.resolveProviderById(42L)).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+8613800138000");
        dto.setContent("Hello");
        dto.setProviderConfigId(42L);

        service.sendNow(dto);

        verify(dispatcher).resolveProviderById(42L);
        verify(dispatcher, never()).resolveProvider();
    }

    // ========== Uniform Batch Send ==========

    @Test
    void sendBatchCreatesOneRecordPerPhoneNumber() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumbers(List.of("+8613800138000", "+8613900139000", "+8613700137000"));
        dto.setContent("Batch message");

        service.sendNow(dto);

        verify(recordService, times(3)).createOne(any());
        verify(recordService, times(3)).updateOne(any());
        verify(adapter, times(3)).send(any(), any(SmsAdapterRequest.class));
    }

    // ========== Differentiated Batch Send ==========

    @Test
    void sendDifferentiatedBatchWithDirectContent() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        BatchSmsItemDTO item1 = new BatchSmsItemDTO();
        item1.setPhoneNumber("+8613800138000");
        item1.setContent("Hello Alice");

        BatchSmsItemDTO item2 = new BatchSmsItemDTO();
        item2.setPhoneNumber("+8613900139000");
        item2.setContent("Hello Bob");

        SendSmsDTO dto = new SendSmsDTO();
        dto.setItems(List.of(item1, item2));

        service.sendNow(dto);

        verify(recordService, times(2)).createOne(any());
        verify(recordService, times(2)).updateOne(any());

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService, times(2)).createOne(captor.capture());
        List<SmsSendRecord> records = captor.getAllValues();
        Assertions.assertEquals("Hello Alice", records.get(0).getContent());
        Assertions.assertEquals("+8613800138000", records.get(0).getPhoneNumber());
        Assertions.assertEquals("Hello Bob", records.get(1).getContent());
        Assertions.assertEquals("+8613900139000", records.get(1).getPhoneNumber());
    }

    @Test
    void sendDifferentiatedBatchWithTemplateVariables() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        SmsTemplate template = new SmsTemplate();
        template.setCode("VERIFY");
        template.setContent("Your code is {{ code }}");

        when(templateService.resolve("VERIFY")).thenReturn(template);
        when(templateService.renderContent(eq(template), eq(Map.of("code", "111111"))))
                .thenReturn("Your code is 111111");
        when(templateService.renderContent(eq(template), eq(Map.of("code", "222222"))))
                .thenReturn("Your code is 222222");

        BatchSmsItemDTO item1 = new BatchSmsItemDTO();
        item1.setPhoneNumber("+8613800138000");
        item1.setTemplateVariables(Map.of("code", "111111"));

        BatchSmsItemDTO item2 = new BatchSmsItemDTO();
        item2.setPhoneNumber("+8613900139000");
        item2.setTemplateVariables(Map.of("code", "222222"));

        SendSmsDTO dto = new SendSmsDTO();
        dto.setTemplateCode("VERIFY");
        dto.setItems(List.of(item1, item2));

        service.sendNow(dto);

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService, times(2)).createOne(captor.capture());
        List<SmsSendRecord> records = captor.getAllValues();
        Assertions.assertEquals("Your code is 111111", records.get(0).getContent());
        Assertions.assertEquals("Your code is 222222", records.get(1).getContent());
    }

    @Test
    void sendDifferentiatedBatchItemFallsBackToParentContent() {
        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        // item has no content and no templateVariables, should fall back to parent content
        BatchSmsItemDTO item = new BatchSmsItemDTO();
        item.setPhoneNumber("+8613800138000");

        SendSmsDTO dto = new SendSmsDTO();
        dto.setContent("Fallback content");
        dto.setItems(List.of(item));

        service.sendNow(dto);

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals("Fallback content", captor.getValue().getContent());
    }

    // ========== Async Send ==========

    @Test
    void sendAsyncPublishesToPulsarWhenAvailable() {
        when(sendProducer.isAvailable()).thenReturn(true);

        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+8613800138000");
        dto.setContent("Async Hello");

        service.sendAsync(dto);

        verify(sendProducer).send(any(SmsSendMessage.class));
        verify(dispatcher, never()).resolveProvider();
    }

    @Test
    void sendAsyncFallsBackToSyncWhenPulsarUnavailable() {
        when(sendProducer.isAvailable()).thenReturn(false);

        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+8613800138000");
        dto.setContent("Fallback Async Hello");

        service.sendAsync(dto);

        verify(sendProducer, never()).send(any());
        verify(dispatcher).resolveProvider();
        verify(adapter).send(any(), any(SmsAdapterRequest.class));
    }

    // ========== Error Code Tracking ==========

    @Test
    void sendFailureSavesErrorCodeToRecord() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(0);
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        SmsSendRecord updated = updateCaptor.getValue();
        Assertions.assertEquals(SmsSendStatus.FAILED, updated.getStatus());
        Assertions.assertEquals("21211", updated.getErrorCode());
        Assertions.assertEquals("Invalid phone number", updated.getErrorMessage());
    }

    @Test
    void sendExceptionSavesNullErrorCode() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(0);
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout"));

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        SmsSendRecord updated = updateCaptor.getValue();
        Assertions.assertNull(updated.getErrorCode());
        Assertions.assertEquals("Connection timeout", updated.getErrorMessage());
    }

    // ========== Retry Logic ==========

    @Test
    void sendFailureTriggersRetryWhenConfigured() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(3);
        config.setRetryIntervalSeconds(30);
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());
        when(retryProducer.isAvailable()).thenReturn(true);

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        SmsSendRecord updated = updateCaptor.getValue();
        Assertions.assertEquals(SmsSendStatus.RETRY, updated.getStatus());
        Assertions.assertEquals(1, updated.getRetryCount());
        Assertions.assertEquals("Invalid phone number", updated.getErrorMessage());
        Assertions.assertEquals("21211", updated.getErrorCode());

        verify(retryProducer).sendDelayed(any(), eq(30));
    }

    @Test
    void sendFailureMarksFailedWhenNoRetryConfigured() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(0);
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        Assertions.assertEquals(SmsSendStatus.FAILED, updateCaptor.getValue().getStatus());

        verify(retryProducer, never()).sendDelayed(any(), anyInt());
    }

    @Test
    void sendFailureMarksFailedWhenRetryProducerUnavailable() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(3);
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());
        when(retryProducer.isAvailable()).thenReturn(false);

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        Assertions.assertEquals(SmsSendStatus.FAILED, updateCaptor.getValue().getStatus());
    }

    @Test
    void sendFailureUsesDefaultRetryInterval() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(3);
        config.setRetryIntervalSeconds(null); // should default to 60
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());
        when(retryProducer.isAvailable()).thenReturn(true);

        service.sendNow("+8613800138000", "Hello");

        verify(retryProducer).sendDelayed(any(), eq(60));
    }

    @Test
    void adapterExceptionTriggersRetry() {
        SmsProviderConfig config = createConfig();
        config.setMaxRetryCount(2);
        config.setRetryIntervalSeconds(10);
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout"));
        when(retryProducer.isAvailable()).thenReturn(true);

        service.sendNow("+8613800138000", "Hello");

        ArgumentCaptor<SmsSendRecord> updateCaptor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        SmsSendRecord updated = updateCaptor.getValue();
        Assertions.assertEquals(SmsSendStatus.RETRY, updated.getStatus());
        Assertions.assertEquals("Connection timeout", updated.getErrorMessage());
    }

    // ========== Retry Send ==========

    @Test
    void retrySendSkipsWhenRecordNotFound() {
        when(recordService.getById(99L)).thenReturn(Optional.empty());

        service.retrySend(99L);

        verify(dispatcher, never()).resolveProviderById(any());
    }

    @Test
    void retrySendSkipsWhenStatusNotRetry() {
        SmsSendRecord record = new SmsSendRecord();
        record.setId(1L);
        record.setStatus(SmsSendStatus.SENT);
        when(recordService.getById(1L)).thenReturn(Optional.of(record));

        service.retrySend(1L);

        verify(dispatcher, never()).resolveProviderById(any());
    }

    @Test
    void retrySendRebuildsAndResends() {
        SmsSendRecord record = new SmsSendRecord();
        record.setId(1L);
        record.setProviderConfigId(10L);
        record.setStatus(SmsSendStatus.RETRY);
        record.setRetryCount(1);
        record.setPhoneNumber("+8613800138000");
        record.setContent("Retry content");

        when(recordService.getById(1L)).thenReturn(Optional.of(record));

        SmsProviderConfig config = createConfig();
        config.setId(10L);
        when(dispatcher.resolveProviderById(10L)).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.retrySend(1L);

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        Assertions.assertEquals(SmsSendStatus.SENT, captor.getValue().getStatus());
    }

    // ========== Template Send ==========

    @Test
    void sendByTemplateResolvesAndRenders() {
        SmsTemplate template = new SmsTemplate();
        template.setCode("VERIFY_CODE");
        template.setContent("Your code is {{ code }}");

        when(templateService.resolve("VERIFY_CODE")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Your code is 123456");

        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendByTemplate("VERIFY_CODE", "+8613800138000", Map.of("code", "123456"));

        // Verify record has template code
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals("VERIFY_CODE", captor.getValue().getTemplateCode());
        Assertions.assertEquals("Your code is 123456", captor.getValue().getContent());
    }

    @Test
    void sendByTemplateBatchSendsToMultipleRecipients() {
        SmsTemplate template = new SmsTemplate();
        template.setCode("ALERT");
        template.setContent("Alert!");

        when(templateService.resolve("ALERT")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Alert!");

        SmsProviderConfig config = createConfig();
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendByTemplate("ALERT", List.of("+8613800138000", "+8613900139000"), Map.of());

        verify(recordService, times(2)).createOne(any());
        verify(adapter, times(2)).send(any(), any(SmsAdapterRequest.class));
    }

    // ========== Template-bound Provider Selection ==========

    @Test
    void sendByTemplateWithoutBindingsUsesTenantDefault() {
        // No bindings configured — should fall back to tenant default provider
        SmsTemplate template = new SmsTemplate();
        template.setCode("SIMPLE");
        template.setContent("Hello");

        when(templateService.resolve("SIMPLE")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Hello");

        SmsProviderConfig config = createConfig(); // Twilio default
        when(dispatcher.resolveProvider()).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendByTemplate("SIMPLE", "+8613800138000", Map.of());

        verify(dispatcher).resolveProvider();
        verify(dispatcher, never()).resolveProviderById(any());
    }

    // ========== Provider Binding Failover ==========

    private SmsTemplateProviderBinding createBinding(Long id, Long templateId, Long providerConfigId,
                                                      String externalTemplateId, String signName,
                                                      int sortOrder) {
        SmsTemplateProviderBinding binding = new SmsTemplateProviderBinding();
        binding.setId(id);
        binding.setTemplateId(templateId);
        binding.setProviderConfigId(providerConfigId);
        binding.setExternalTemplateId(externalTemplateId);
        binding.setSignName(signName);
        binding.setSortOrder(sortOrder);
        binding.setIsEnabled(true);
        return binding;
    }

    @Test
    void failoverFirstProviderFailsSecondSucceeds() {
        // Template with ID so bindings are resolved
        SmsTemplate template = new SmsTemplate();
        template.setId(100L);
        template.setCode("VERIFY");
        template.setContent("Code: {{ code }}");

        when(templateService.resolve("VERIFY")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Code: 123456");

        // Two bindings: Twilio (sort=0) fails, Aliyun (sort=1) succeeds
        SmsTemplateProviderBinding binding1 = createBinding(1L, 100L, 10L, null, null, 0);
        SmsTemplateProviderBinding binding2 = createBinding(2L, 100L, 20L, "SMS_99999", "阿里签名", 1);
        when(bindingService.findByTemplateId(100L)).thenReturn(List.of(binding1, binding2));

        // Twilio config (fails)
        SmsProviderConfig twilioConfig = createConfig();
        twilioConfig.setId(10L);
        when(dispatcher.resolveProviderById(10L)).thenReturn(twilioConfig);
        SmsProviderAdapter twilioAdapter = mock(SmsProviderAdapter.class);
        SmsSendResult twilioResult = failureResult();
        when(twilioAdapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(twilioResult);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(twilioAdapter);

        // Aliyun config (succeeds)
        SmsProviderConfig aliyunConfig = new SmsProviderConfig();
        aliyunConfig.setId(20L);
        aliyunConfig.setProviderType(SmsProvider.ALIYUN);
        aliyunConfig.setIsEnabled(true);
        when(dispatcher.resolveProviderById(20L)).thenReturn(aliyunConfig);
        SmsProviderAdapter aliyunAdapter = mock(SmsProviderAdapter.class);
        when(aliyunAdapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());
        when(adapterFactory.getAdapter(SmsProvider.ALIYUN)).thenReturn(aliyunAdapter);

        service.sendByTemplate("VERIFY", "+8613800138000", Map.of("code", "123456"));

        // Should have tried both providers
        verify(twilioAdapter).send(eq(twilioConfig), any(SmsAdapterRequest.class));
        ArgumentCaptor<SmsAdapterRequest> aliyunReqCaptor = ArgumentCaptor.forClass(SmsAdapterRequest.class);
        verify(aliyunAdapter).send(eq(aliyunConfig), aliyunReqCaptor.capture());
        Assertions.assertEquals("SMS_99999", aliyunReqCaptor.getValue().getExternalTemplateId());
        Assertions.assertEquals("阿里签名", aliyunReqCaptor.getValue().getSignName());

        // Record should be updated to SENT with Aliyun's info
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        SmsSendRecord updated = captor.getValue();
        Assertions.assertEquals(SmsSendStatus.SENT, updated.getStatus());
        Assertions.assertEquals(20L, updated.getProviderConfigId());
        Assertions.assertEquals(SmsProvider.ALIYUN, updated.getProviderType());
        Assertions.assertNotNull(updated.getSentAt());
    }

    @Test
    void failoverAllProvidersFail() {
        SmsTemplate template = new SmsTemplate();
        template.setId(100L);
        template.setCode("VERIFY");
        template.setContent("Code: {{ code }}");

        when(templateService.resolve("VERIFY")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Code: 123456");

        // Two bindings, both fail
        SmsTemplateProviderBinding binding1 = createBinding(1L, 100L, 10L, null, null, 0);
        SmsTemplateProviderBinding binding2 = createBinding(2L, 100L, 20L, "SMS_99999", "签名", 1);
        when(bindingService.findByTemplateId(100L)).thenReturn(List.of(binding1, binding2));

        SmsProviderConfig config1 = createConfig();
        config1.setId(10L);
        config1.setMaxRetryCount(0);
        when(dispatcher.resolveProviderById(10L)).thenReturn(config1);
        SmsProviderAdapter adapter1 = mock(SmsProviderAdapter.class);
        when(adapter1.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter1);

        SmsProviderConfig config2 = new SmsProviderConfig();
        config2.setId(20L);
        config2.setProviderType(SmsProvider.ALIYUN);
        config2.setMaxRetryCount(0);
        config2.setIsEnabled(true);
        when(dispatcher.resolveProviderById(20L)).thenReturn(config2);
        SmsProviderAdapter adapter2 = mock(SmsProviderAdapter.class);
        SmsSendResult aliyunFail = new SmsSendResult();
        aliyunFail.setSuccess(false);
        aliyunFail.setErrorCode("ALI_ERR");
        aliyunFail.setErrorMessage("Aliyun failure");
        when(adapter2.send(any(), any(SmsAdapterRequest.class))).thenReturn(aliyunFail);
        when(adapterFactory.getAdapter(SmsProvider.ALIYUN)).thenReturn(adapter2);

        service.sendByTemplate("VERIFY", "+8613800138000", Map.of("code", "123456"));

        // Record should be updated to FAILED with last provider's error
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        SmsSendRecord updated = captor.getValue();
        Assertions.assertEquals(SmsSendStatus.FAILED, updated.getStatus());
        Assertions.assertEquals("ALI_ERR", updated.getErrorCode());
        Assertions.assertEquals("Aliyun failure", updated.getErrorMessage());
    }

    @Test
    void failoverUsesBindingExternalTemplateIdAndSignName() {
        SmsTemplate template = new SmsTemplate();
        template.setId(100L);
        template.setCode("NOTIFY");
        template.setContent("Hello");

        when(templateService.resolve("NOTIFY")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Hello");

        // Single binding with provider-specific overrides
        SmsTemplateProviderBinding binding = createBinding(1L, 100L, 10L,
                "SMS_PROVIDER_TMPL", "ProviderSign", 0);
        when(bindingService.findByTemplateId(100L)).thenReturn(List.of(binding));

        SmsProviderConfig config = createConfig();
        config.setId(10L);
        when(dispatcher.resolveProviderById(10L)).thenReturn(config);
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(adapter);
        when(adapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());

        service.sendByTemplate("NOTIFY", "+8613800138000", Map.of());

        ArgumentCaptor<SmsAdapterRequest> bindingReqCaptor = ArgumentCaptor.forClass(SmsAdapterRequest.class);
        verify(adapter).send(eq(config), bindingReqCaptor.capture());
        Assertions.assertEquals("+8613800138000", bindingReqCaptor.getValue().getPhoneNumber());
        Assertions.assertEquals("Hello", bindingReqCaptor.getValue().getContent());
        Assertions.assertEquals("SMS_PROVIDER_TMPL", bindingReqCaptor.getValue().getExternalTemplateId());
        Assertions.assertEquals("ProviderSign", bindingReqCaptor.getValue().getSignName());
    }

    @Test
    void failoverFirstProviderThrowsExceptionSecondSucceeds() {
        SmsTemplate template = new SmsTemplate();
        template.setId(100L);
        template.setCode("VERIFY");
        template.setContent("Code");

        when(templateService.resolve("VERIFY")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Code");

        SmsTemplateProviderBinding binding1 = createBinding(1L, 100L, 10L, null, null, 0);
        SmsTemplateProviderBinding binding2 = createBinding(2L, 100L, 20L, null, null, 1);
        when(bindingService.findByTemplateId(100L)).thenReturn(List.of(binding1, binding2));

        // First provider throws exception
        SmsProviderConfig config1 = createConfig();
        config1.setId(10L);
        when(dispatcher.resolveProviderById(10L)).thenReturn(config1);
        SmsProviderAdapter throwingAdapter = mock(SmsProviderAdapter.class);
        when(throwingAdapter.send(any(), any(SmsAdapterRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout"));
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(throwingAdapter);

        // Second provider succeeds
        SmsProviderConfig config2 = new SmsProviderConfig();
        config2.setId(20L);
        config2.setProviderType(SmsProvider.INFOBIP);
        config2.setIsEnabled(true);
        when(dispatcher.resolveProviderById(20L)).thenReturn(config2);
        SmsProviderAdapter infobipAdapter = mock(SmsProviderAdapter.class);
        when(infobipAdapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());
        when(adapterFactory.getAdapter(SmsProvider.INFOBIP)).thenReturn(infobipAdapter);

        service.sendByTemplate("VERIFY", "+8613800138000", Map.of());

        // Should have failovered to Infobip
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        SmsSendRecord updated = captor.getValue();
        Assertions.assertEquals(SmsSendStatus.SENT, updated.getStatus());
        Assertions.assertEquals(20L, updated.getProviderConfigId());
        Assertions.assertEquals(SmsProvider.INFOBIP, updated.getProviderType());
    }

    @Test
    void failoverFallsToPlatformBindingsWhenNoTenantBindings() {
        SmsTemplate template = new SmsTemplate();
        template.setId(100L);
        template.setCode("PLATFORM");
        template.setContent("Platform msg");

        when(templateService.resolve("PLATFORM")).thenReturn(template);
        when(templateService.renderContent(any(), any())).thenReturn("Platform msg");

        // No tenant bindings, but platform bindings exist
        when(bindingService.findByTemplateId(100L)).thenReturn(List.of());
        SmsTemplateProviderBinding platformBinding = createBinding(1L, 100L, 30L, "P_TMPL", "PSign", 0);
        when(bindingService.findPlatformBindingsByTemplateId(100L)).thenReturn(List.of(platformBinding));

        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(30L);
        config.setProviderType(SmsProvider.INFOBIP);
        config.setIsEnabled(true);
        when(dispatcher.resolveProviderById(30L)).thenReturn(config);
        SmsProviderAdapter infobipAdapter = mock(SmsProviderAdapter.class);
        when(infobipAdapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());
        when(adapterFactory.getAdapter(SmsProvider.INFOBIP)).thenReturn(infobipAdapter);

        service.sendByTemplate("PLATFORM", "+8613800138000", Map.of());

        ArgumentCaptor<SmsAdapterRequest> platformReqCaptor = ArgumentCaptor.forClass(SmsAdapterRequest.class);
        verify(infobipAdapter).send(eq(config), platformReqCaptor.capture());
        Assertions.assertEquals("+8613800138000", platformReqCaptor.getValue().getPhoneNumber());
        Assertions.assertEquals("Platform msg", platformReqCaptor.getValue().getContent());
        Assertions.assertEquals("P_TMPL", platformReqCaptor.getValue().getExternalTemplateId());
        Assertions.assertEquals("PSign", platformReqCaptor.getValue().getSignName());
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        Assertions.assertEquals(SmsSendStatus.SENT, captor.getValue().getStatus());
    }

    @Test
    void retrySendWithFailoverBindingsReResolvesProviders() {
        // Existing record from a template-based send
        SmsSendRecord record = new SmsSendRecord();
        record.setId(1L);
        record.setProviderConfigId(10L);
        record.setStatus(SmsSendStatus.RETRY);
        record.setRetryCount(1);
        record.setPhoneNumber("+8613800138000");
        record.setContent("Retry content");
        record.setTemplateCode("VERIFY");

        when(recordService.getById(1L)).thenReturn(Optional.of(record));

        // Template resolves with bindings
        SmsTemplate template = new SmsTemplate();
        template.setId(100L);
        template.setCode("VERIFY");
        template.setContent("Retry content");
        when(templateService.resolve("VERIFY")).thenReturn(template);

        SmsTemplateProviderBinding binding1 = createBinding(1L, 100L, 10L, null, null, 0);
        SmsTemplateProviderBinding binding2 = createBinding(2L, 100L, 20L, "SMS_NEW", null, 1);
        when(bindingService.findByTemplateId(100L)).thenReturn(List.of(binding1, binding2));

        // First provider fails again
        SmsProviderConfig config1 = createConfig();
        config1.setId(10L);
        when(dispatcher.resolveProviderById(10L)).thenReturn(config1);
        SmsProviderAdapter twilioAdapter = mock(SmsProviderAdapter.class);
        when(twilioAdapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(failureResult());
        when(adapterFactory.getAdapter(SmsProvider.TWILIO)).thenReturn(twilioAdapter);

        // Second provider succeeds
        SmsProviderConfig config2 = new SmsProviderConfig();
        config2.setId(20L);
        config2.setProviderType(SmsProvider.ALIYUN);
        config2.setIsEnabled(true);
        when(dispatcher.resolveProviderById(20L)).thenReturn(config2);
        SmsProviderAdapter aliyunAdapter = mock(SmsProviderAdapter.class);
        when(aliyunAdapter.send(any(), any(SmsAdapterRequest.class))).thenReturn(successResult());
        when(adapterFactory.getAdapter(SmsProvider.ALIYUN)).thenReturn(aliyunAdapter);

        service.retrySend(1L);

        // Should have retried with failover and succeeded on Aliyun
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        SmsSendRecord updated = captor.getValue();
        Assertions.assertEquals(SmsSendStatus.SENT, updated.getStatus());
        Assertions.assertEquals(20L, updated.getProviderConfigId());
        Assertions.assertEquals(SmsProvider.ALIYUN, updated.getProviderType());
    }
}
