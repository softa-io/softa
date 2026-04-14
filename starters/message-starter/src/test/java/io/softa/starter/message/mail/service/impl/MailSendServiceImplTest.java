package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Optional;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mail.dto.BatchMailItemDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.message.MailRetryProducer;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.support.MailSenderFactory;
import io.softa.starter.message.mail.support.MailServerDispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailSendServiceImplTest {

    private MailSendServiceImpl service;
    private MailServerDispatcher dispatcher;
    private MailSenderFactory senderFactory;
    private MailSendRecordService recordService;
    private MailTemplateService templateService;
    private MailRetryProducer retryProducer;

    @BeforeEach
    void setUp() {
        service = new MailSendServiceImpl();
        dispatcher = mock(MailServerDispatcher.class);
        senderFactory = mock(MailSenderFactory.class);
        recordService = mock(MailSendRecordService.class);
        templateService = mock(MailTemplateService.class);
        retryProducer = mock(MailRetryProducer.class);

        ReflectionTestUtils.setField(service, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(service, "senderFactory", senderFactory);
        ReflectionTestUtils.setField(service, "recordService", recordService);
        ReflectionTestUtils.setField(service, "templateService", templateService);
        ReflectionTestUtils.setField(service, "retryProducer", retryProducer);
    }

    private MailSendServerConfig createConfig() {
        MailSendServerConfig config = new MailSendServerConfig();
        config.setId(1L);
        config.setHost("smtp.example.com");
        config.setPort(587);
        config.setUsername("sender@example.com");
        config.setFromAddress("sender@example.com");
        config.setFromName("Test Sender");
        config.setIsEnabled(true);
        return config;
    }

    // ========== Record Building Tests ==========

    @Test
    void buildRecordPopulatesReadReceiptFromDto() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");
        dto.setReadReceiptRequested(true);

        service.sendNow(dto);

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        MailSendRecord record = captor.getValue();

        Assertions.assertTrue(record.getReadReceiptRequested());
    }

    @Test
    void buildRecordPopulatesReadReceiptFromConfigDefault() throws Exception {
        MailSendServerConfig config = createConfig();
        config.setReadReceiptEnabled(true);
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");
        // readReceiptRequested is null — should fall back to config default

        service.sendNow(dto);

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertTrue(captor.getValue().getReadReceiptRequested());
    }

    @Test
    void buildRecordPopulatesPriority() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Urgent");
        dto.setTextBody("Important message");
        dto.setPriority(MailPriority.HIGH);

        service.sendNow(dto);

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals("High", captor.getValue().getPriority());
    }

    @Test
    void buildRecordInitialStatusIsPending() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        // Capture the status at createOne time (before executeSend mutates the object)
        final MailSendStatus[] statusAtCreation = new MailSendStatus[1];
        final int[] retryCountAtCreation = new int[1];
        doAnswer(invocation -> {
            MailSendRecord r = invocation.getArgument(0);
            statusAtCreation[0] = r.getStatus();
            retryCountAtCreation[0] = r.getRetryCount();
            return null;
        }).when(recordService).createOne(any());

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");

        service.sendNow(dto);

        Assertions.assertEquals(MailSendStatus.PENDING, statusAtCreation[0]);
        Assertions.assertEquals(0, retryCountAtCreation[0]);
    }

    // ========== Send Success ==========

    @Test
    void sendSuccessUpdatesStatusToSent() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");

        service.sendNow(dto);

        // The record should be updated to SENT
        ArgumentCaptor<MailSendRecord> updateCaptor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        Assertions.assertEquals(MailSendStatus.SENT, updateCaptor.getValue().getStatus());
        Assertions.assertNotNull(updateCaptor.getValue().getSentAt());
    }

    // ========== Retry Logic ==========

    @Test
    void sendFailureTriggersRetryWhenConfigured() throws Exception {
        MailSendServerConfig config = createConfig();
        config.setMaxRetryCount(3);
        config.setRetryIntervalSeconds(30);
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP error"))
                .when(sender).send(any(MimeMessage.class));
        when(retryProducer.isAvailable()).thenReturn(true);

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");

        service.sendNow(dto);

        // Record should be updated with RETRY status
        ArgumentCaptor<MailSendRecord> updateCaptor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        MailSendRecord updated = updateCaptor.getValue();
        Assertions.assertEquals(MailSendStatus.RETRY, updated.getStatus());
        Assertions.assertEquals(1, updated.getRetryCount());
        Assertions.assertEquals("SMTP error", updated.getErrorMessage());

        // Retry producer should be called
        verify(retryProducer).sendDelayed(any(), any(int.class));
    }

    @Test
    void sendFailureMarksFailedWhenNoRetryConfigured() throws Exception {
        MailSendServerConfig config = createConfig();
        config.setMaxRetryCount(0); // No retry
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection refused"))
                .when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");

        service.sendNow(dto);

        ArgumentCaptor<MailSendRecord> updateCaptor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        Assertions.assertEquals(MailSendStatus.FAILED, updateCaptor.getValue().getStatus());
        Assertions.assertEquals("Connection refused", updateCaptor.getValue().getErrorMessage());

        // Retry producer should NOT be called
        verify(retryProducer, never()).sendDelayed(any(), any(int.class));
    }

    @Test
    void sendFailureMarksFailedWhenRetryProducerUnavailable() throws Exception {
        MailSendServerConfig config = createConfig();
        config.setMaxRetryCount(3);
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("error"))
                .when(sender).send(any(MimeMessage.class));
        when(retryProducer.isAvailable()).thenReturn(false);

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test");
        dto.setTextBody("Hello");

        service.sendNow(dto);

        ArgumentCaptor<MailSendRecord> updateCaptor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).updateOne(updateCaptor.capture());
        Assertions.assertEquals(MailSendStatus.FAILED, updateCaptor.getValue().getStatus());
    }

    // ========== Retry Send ==========

    @Test
    void retrySendSkipsWhenRecordNotFound() {
        when(recordService.getById(99L)).thenReturn(Optional.empty());

        service.retrySend(99L);

        verify(dispatcher, never()).resolveSendById(any());
    }

    @Test
    void retrySendSkipsWhenStatusNotRetry() {
        MailSendRecord record = new MailSendRecord();
        record.setId(1L);
        record.setStatus(MailSendStatus.SENT);
        when(recordService.getById(1L)).thenReturn(Optional.of(record));

        service.retrySend(1L);

        verify(dispatcher, never()).resolveSendById(any());
    }

    @Test
    void retrySendRebuildsAndResends() throws Exception {
        MailSendRecord record = new MailSendRecord();
        record.setId(1L);
        record.setServerConfigId(10L);
        record.setStatus(MailSendStatus.RETRY);
        record.setRetryCount(1);
        record.setToAddresses("[\"recipient@example.com\"]");
        record.setSubject("Test Retry");
        record.setContentType("TEXT");
        record.setBodyPreview("Retry body");
        record.setReadReceiptRequested(false);

        when(recordService.getById(1L)).thenReturn(Optional.of(record));

        MailSendServerConfig config = createConfig();
        config.setId(10L);
        when(dispatcher.resolveSendById(10L)).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        service.retrySend(1L);

        // Should update record to SENT
        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).updateOne(captor.capture());
        Assertions.assertEquals(MailSendStatus.SENT, captor.getValue().getStatus());
    }

    // ========== Template Priority ==========

    @Test
    void sendByTemplateAppliesTemplatePriority() throws Exception {
        MailTemplate template = new MailTemplate();
        template.setCode("ALERT");
        template.setSubject("Alert: {{ event }}");
        template.setBody("<p>Alert body</p>");
        template.setDefaultPriority("HIGH");
        template.setIncludePlainText(false);

        when(templateService.resolve("ALERT")).thenReturn(template);
        when(templateService.renderSubject(any(), any())).thenReturn("Alert: fire");
        when(templateService.renderBody(any(), any())).thenReturn("<p>Alert body</p>");

        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        service.sendByTemplate("ALERT", "admin@example.com", java.util.Map.of("event", "fire"));

        // Record should have priority set from template
        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals("High", captor.getValue().getPriority());
    }

    // ========== Header Injection on MimeMessage ==========

    @Test
    void sendSetsReadReceiptHeaders() throws Exception {
        MailSendServerConfig config = createConfig();
        config.setReadReceiptEnabled(true);
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Test Headers");
        dto.setTextBody("Body");

        service.sendNow(dto);

        // Verify headers on the MimeMessage
        Assertions.assertEquals("sender@example.com", mimeMessage.getHeader("Disposition-Notification-To", null));
        Assertions.assertEquals("sender@example.com", mimeMessage.getHeader("Return-Receipt-To", null));
    }

    @Test
    void sendSetsPriorityHeaders() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Urgent");
        dto.setTextBody("Please respond");
        dto.setPriority(MailPriority.HIGH);

        service.sendNow(dto);

        // All three priority headers should be set
        Assertions.assertEquals("1", mimeMessage.getHeader("X-Priority", null));
        Assertions.assertEquals("High", mimeMessage.getHeader("Importance", null));
        Assertions.assertEquals("High", mimeMessage.getHeader("X-MSMail-Priority", null));
    }

    @Test
    void sendDoesNotSetPriorityHeadersWhenNull() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Normal");
        dto.setTextBody("Hello");
        // No priority set

        service.sendNow(dto);

        Assertions.assertNull(mimeMessage.getHeader("X-Priority", null));
        Assertions.assertNull(mimeMessage.getHeader("Importance", null));
        Assertions.assertNull(mimeMessage.getHeader("X-MSMail-Priority", null));
    }

    @Test
    void sendDoesNotSetReceiptHeadersWhenDisabled() throws Exception {
        MailSendServerConfig config = createConfig();
        config.setReadReceiptEnabled(false);
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("recipient@example.com"));
        dto.setSubject("Normal");
        dto.setTextBody("Hello");
        dto.setReadReceiptRequested(false);

        service.sendNow(dto);

        Assertions.assertNull(mimeMessage.getHeader("Disposition-Notification-To", null));
        Assertions.assertNull(mimeMessage.getHeader("Return-Receipt-To", null));
    }

    // ========== Differentiated Batch Send ==========

    @Test
    void sendDifferentiatedBatchWithDirectContent() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage1 = new MimeMessage((jakarta.mail.Session) null);
        MimeMessage mimeMessage2 = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage1, mimeMessage2);
        doNothing().when(sender).send(any(MimeMessage.class));

        BatchMailItemDTO item1 = new BatchMailItemDTO();
        item1.setTo(List.of("alice@example.com"));
        item1.setSubject("Hello Alice");
        item1.setHtmlBody("<p>Dear Alice</p>");

        BatchMailItemDTO item2 = new BatchMailItemDTO();
        item2.setTo(List.of("bob@example.com"));
        item2.setSubject("Hello Bob");
        item2.setHtmlBody("<p>Dear Bob</p>");

        SendMailDTO dto = new SendMailDTO();
        dto.setItems(List.of(item1, item2));

        service.sendNow(dto);

        // Should create 2 records (one per item)
        verify(recordService, times(2)).createOne(any());
        verify(recordService, times(2)).updateOne(any());
        verify(sender, times(2)).send(any(MimeMessage.class));

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService, times(2)).createOne(captor.capture());
        List<MailSendRecord> records = captor.getAllValues();
        Assertions.assertEquals("[\"alice@example.com\"]", records.get(0).getToAddresses());
        Assertions.assertEquals("Hello Alice", records.get(0).getSubject());
        Assertions.assertEquals("[\"bob@example.com\"]", records.get(1).getToAddresses());
        Assertions.assertEquals("Hello Bob", records.get(1).getSubject());
    }

    @Test
    void sendDifferentiatedBatchWithTemplateVariables() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage1 = new MimeMessage((jakarta.mail.Session) null);
        MimeMessage mimeMessage2 = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage1, mimeMessage2);
        doNothing().when(sender).send(any(MimeMessage.class));

        MailTemplate template = new MailTemplate();
        template.setCode("WELCOME");
        template.setSubject("Welcome {{ name }}");
        template.setBody("<p>Hello {{ name }}, your code is {{ code }}</p>");
        template.setIncludePlainText(false);

        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(templateService.renderSubject(any(), any()))
                .thenReturn("Welcome Alice", "Welcome Bob");
        when(templateService.renderBody(any(), any()))
                .thenReturn("<p>Hello Alice, your code is 111</p>", "<p>Hello Bob, your code is 222</p>");

        BatchMailItemDTO item1 = new BatchMailItemDTO();
        item1.setTo(List.of("alice@example.com"));
        item1.setTemplateVariables(java.util.Map.of("name", "Alice", "code", "111"));

        BatchMailItemDTO item2 = new BatchMailItemDTO();
        item2.setTo(List.of("bob@example.com"));
        item2.setTemplateVariables(java.util.Map.of("name", "Bob", "code", "222"));

        SendMailDTO dto = new SendMailDTO();
        dto.setTemplateCode("WELCOME");
        dto.setItems(List.of(item1, item2));

        service.sendNow(dto);

        verify(recordService, times(2)).createOne(any());

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService, times(2)).createOne(captor.capture());
        List<MailSendRecord> records = captor.getAllValues();
        Assertions.assertEquals("Welcome Alice", records.get(0).getSubject());
        Assertions.assertEquals("Welcome Bob", records.get(1).getSubject());
    }

    @Test
    void sendDifferentiatedBatchFallsBackToParentContent() throws Exception {
        MailSendServerConfig config = createConfig();
        when(dispatcher.resolveSend()).thenReturn(config);

        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(senderFactory.getSender(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(sender).send(any(MimeMessage.class));

        // Item has no content and no templateVariables; should fall back to parent
        BatchMailItemDTO item = new BatchMailItemDTO();
        item.setTo(List.of("user@example.com"));

        SendMailDTO dto = new SendMailDTO();
        dto.setSubject("Shared Subject");
        dto.setHtmlBody("<p>Shared Body</p>");
        dto.setItems(List.of(item));

        service.sendNow(dto);

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        Assertions.assertEquals("Shared Subject", captor.getValue().getSubject());
        Assertions.assertTrue(captor.getValue().getBodyPreview().contains("Shared Body"));
    }
}
