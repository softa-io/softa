package io.softa.starter.message.service.internal;

import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.support.SmsRoutingPlanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Acceptance-side contract for one SMS message. */
class SmsMessageHandlerTest {

    private SmsSendRecordService recordService;
    private SmsTemplateService templateService;
    private SmsRoutingPlanner routingPlanner;
    private OutboxRecordWriter outboxRecordWriter;
    private SmsMessageHandler handler;

    @BeforeEach
    void setUp() {
        recordService = mock(SmsSendRecordService.class);
        templateService = mock(SmsTemplateService.class);
        routingPlanner = mock(SmsRoutingPlanner.class);
        outboxRecordWriter = mock(OutboxRecordWriter.class);
        handler = new SmsMessageHandler(recordService, templateService, routingPlanner, outboxRecordWriter);

        when(outboxRecordWriter.persistAndEnqueue(any(), eq("SmsSendRecord"), eq(TopicRoute.SMS_SEND)))
                .thenAnswer(invocation -> ((Supplier<Long>) invocation.getArgument(0)).get());
        when(recordService.createOne(any(SmsSendRecord.class))).thenReturn(55L);
        when(routingPlanner.plan(any(SmsRoutingPlanner.RoutingRequest.class)))
                .thenReturn(new SmsRoutingPlanner.Plan(config(), "TPL-1", "SIGN", null, null));
    }

    @Test
    void sendRoutesPersistsAndEnqueuesOutbox() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+111");
        dto.setContent("hi");

        Long id = handler.send(dto);

        assertEquals(55L, id);
        SmsSendRecord record = capturedRecord();
        assertEquals("+111", record.getPhoneNumber());
        assertEquals("hi", record.getContent());
    }

    @Test
    void templateRequestRendersContent() {
        SmsTemplate template = new SmsTemplate();
        when(templateService.resolve("VERIFY")).thenReturn(template);
        when(templateService.renderContent(eq(template), any())).thenReturn("code is 123456");

        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+111");
        dto.setTemplateCode("VERIFY");
        dto.setTemplateVariables(Map.of("code", "123456"));

        handler.send(dto);

        assertEquals("code is 123456", capturedRecord().getContent());
        assertNull(dto.getContent());
        verify(templateService).resolve("VERIFY");
        verify(routingPlanner).plan(new SmsRoutingPlanner.RoutingRequest(
                "+111", null, "VERIFY", null, null, template));
    }

    @Test
    void missingPhoneIsRejectedBeforePersisting() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setContent("hi");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(templateService, routingPlanner, recordService, outboxRecordWriter);
    }

    @Test
    void missingContentAndTemplateIsRejectedBeforePersisting() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+111");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService, outboxRecordWriter);
    }

    private SmsProviderConfig config() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(20L);
        config.setProviderType(SmsProvider.TWILIO);
        return config;
    }

    private SmsSendRecord capturedRecord() {
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        return captor.getValue();
    }
}
