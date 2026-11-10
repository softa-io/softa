package io.softa.starter.message.service.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.support.MailBodies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acceptance contract for one mail message: only the caller's mistakes are rejected, and the request
 * is stored as it was made — nothing about templates, servers or quota is looked at here (that is
 * the first delivery attempt's job, see {@code MailDeliveryProcessorTest}).
 */
class MailMessageHandlerTest {

    private MailSendRecordService recordService;
    private MailMessageHandler handler;

    @BeforeEach
    void setUp() {
        recordService = mock(MailSendRecordService.class);
        handler = new MailMessageHandler(recordService);
        when(recordService.createOne(any(MailSendRecord.class))).thenReturn(42L);
    }

    @Test
    void directContentIsStoredAsAPendingRecord() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("alice@example.com"));
        dto.setSubject("Hi");
        dto.setTextBody("Body");

        Long id = ContextHolder.callWith(new Context("trace-1"), () -> handler.send(dto));

        assertEquals(42L, id);
        MailSendRecord record = capturedRecord();
        assertEquals(MailSendStatus.PENDING, record.getStatus());
        assertEquals("Hi", record.getSubject());
        assertEquals("Body", record.getBodyText());
        assertEquals("trace-1", record.getTraceId());
        assertFalse(record.getPlatformScope());
        // Fixed only when the record is prepared.
        assertNull(record.getFromAddress());
        assertNull(record.getServerConfigId());
    }

    @Test
    void aTemplateRequestIsStoredUnrendered() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("alice@example.com"));
        dto.setTemplateCode("WELCOME");
        dto.setTemplateVariables(Map.of("name", "Alice"));

        handler.send(dto);

        MailSendRecord record = capturedRecord();
        assertEquals("WELCOME", record.getTemplateCode());
        assertEquals(Map.of("name", "Alice"), JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
        assertNull(record.getSubject());
        assertNull(record.getBodyHtml());
        assertNull(record.getBodyText());
        assertNull(record.getBodyMode());
    }

    @Test
    void scopeAndExplicitChoicesAreKeptForTheFirstAttempt() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("alice@example.com"));
        dto.setTemplateId(7L);
        dto.setServerConfigId(99L);
        dto.setReplyTo("a@example.com; b@example.com");
        dto.setScope(MessageScope.PLATFORM);

        handler.send(dto);

        MailSendRecord record = capturedRecord();
        assertTrue(record.getPlatformScope());
        assertEquals(7L, record.getTemplateId());
        assertEquals(99L, record.getServerConfigId());
        assertEquals("a@example.com, b@example.com", record.getReplyTo());
    }

    @Test
    void bodyOverTheLimitIsRejected() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("alice@example.com"));
        dto.setSubject("Hi");
        dto.setTextBody("x".repeat(MailBodies.MAX_CHARS + 1));

        BusinessException ex = assertThrows(BusinessException.class, () -> handler.send(dto));
        assertTrue(ex.getMessage().contains("maximum length"));
        verifyNoInteractions(recordService);
    }

    @Test
    void missingRecipientsIsRejectedBeforePersisting() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTextBody("Body");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void malformedAddressIsRejectedBeforePersisting() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("not-an-address"));
        dto.setTextBody("Body");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void neitherContentNorTemplateIsRejectedBeforePersisting() {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("alice@example.com"));

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void aBatchIsValidatedWholeBeforeAnythingIsWritten() {
        SendMailDTO good = new SendMailDTO();
        good.setTo(List.of("alice@example.com"));
        good.setTextBody("Body");
        SendMailDTO bad = new SendMailDTO();
        bad.setTo(List.of("bob@example.com"));

        assertThrows(BusinessException.class, () -> handler.sendBatch(List.of(good, bad)));
        verify(recordService, never()).createOne(any(MailSendRecord.class));
    }

    @Test
    void aPlatformSendFromATenantStaysInThatTenantsBooks() {
        // A subscription reminder: the platform's template and server, the tenant's record.
        List<Long> insertedFor = new ArrayList<>();
        when(recordService.createOne(any(MailSendRecord.class))).thenAnswer(invocation -> {
            insertedFor.add(ContextHolder.getContext().getTenantId());
            return 42L;
        });
        Context tenant = new Context("trace-1");
        tenant.setTenantId(42L);
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of("admin@example.com"));
        dto.setTemplateCode("subscription.expiry-reminder");
        dto.setScope(MessageScope.PLATFORM);

        ContextHolder.runWith(tenant, () -> handler.send(dto));

        assertEquals(List.of(42L), insertedFor);
        assertTrue(capturedRecord().getPlatformScope());
    }

    private MailSendRecord capturedRecord() {
        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        return captor.getValue();
    }
}
