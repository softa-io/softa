package io.softa.starter.message.service.internal;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-trip coverage for the four {@link BodyMode}s through
 * {@code buildRecord} → {@code rebuildDTO}. Both bodies are persisted verbatim
 * (option B unified storage) so retry replays bit-for-bit, with no
 * derivation at retry time.
 *
 * <p>The {@code DERIVED} vs {@code AUTHORED} distinction is preserved as
 * audit metadata on the record — both modes persist the same shape of data
 * (HTML + plain), but the mode field tells future SQL queries whether the
 * plain text was machine-generated or human-reviewed.
 */
class MailMessageBodyModeTest {

    @Test
    void htmlOnlyRoundTrip() throws Exception {
        SendMailDTO dto = new SendMailDTO();
        dto.setHtmlBody("<p>Welcome</p>");

        MailSendRecord record = invokeBuildRecord(dto);
        Assertions.assertEquals(BodyMode.HTML, record.getBodyMode());
        Assertions.assertEquals("<p>Welcome</p>", record.getBodyHtml());
        Assertions.assertNull(record.getBodyText());

        SendMailDTO rebuilt = invokeRebuildDTO(record);
        Assertions.assertEquals(BodyMode.HTML, rebuilt.getBodyMode());
        Assertions.assertEquals("<p>Welcome</p>", rebuilt.getHtmlBody());
        Assertions.assertNull(rebuilt.getTextBody(),
                "HTML-only retry must NOT carry a derived plain alt");
    }

    @Test
    void plainOnlyRoundTrip() throws Exception {
        SendMailDTO dto = new SendMailDTO();
        dto.setTextBody("Welcome");

        MailSendRecord record = invokeBuildRecord(dto);
        Assertions.assertEquals(BodyMode.PLAIN, record.getBodyMode());
        Assertions.assertEquals("Welcome", record.getBodyText());
        Assertions.assertNull(record.getBodyHtml());

        SendMailDTO rebuilt = invokeRebuildDTO(record);
        Assertions.assertEquals(BodyMode.PLAIN, rebuilt.getBodyMode());
        Assertions.assertEquals("Welcome", rebuilt.getTextBody());
        Assertions.assertNull(rebuilt.getHtmlBody());
    }

    @Test
    void authoredAlternativeRoundTrip() throws Exception {
        // Caller (or template) supplies both bodies, declares AUTHORED intent.
        SendMailDTO dto = new SendMailDTO();
        dto.setHtmlBody("<p>Welcome <b>back</b></p>");
        dto.setTextBody("Hand-crafted plain version");
        dto.setBodyMode(BodyMode.HTML_WITH_AUTHORED_PLAIN);

        MailSendRecord record = invokeBuildRecord(dto);
        Assertions.assertEquals(BodyMode.HTML_WITH_AUTHORED_PLAIN, record.getBodyMode());
        Assertions.assertEquals("<p>Welcome <b>back</b></p>", record.getBodyHtml());
        Assertions.assertEquals("Hand-crafted plain version", record.getBodyText(),
                "Authored plain must be persisted verbatim, never overwritten by stripped HTML");

        SendMailDTO rebuilt = invokeRebuildDTO(record);
        Assertions.assertEquals(BodyMode.HTML_WITH_AUTHORED_PLAIN, rebuilt.getBodyMode());
        Assertions.assertEquals("<p>Welcome <b>back</b></p>", rebuilt.getHtmlBody());
        Assertions.assertEquals("Hand-crafted plain version", rebuilt.getTextBody(),
                "Retry must replay the original authored plain, not re-derive");
    }

    @Test
    void derivedAlternativeRoundTrip() throws Exception {
        // Template-driven send filled DTO with html + machine-derived plain.
        SendMailDTO dto = new SendMailDTO();
        dto.setHtmlBody("<p>Welcome <b>back</b></p>");
        dto.setTextBody("Welcome back");   // derived during template normalization
        dto.setBodyMode(BodyMode.HTML_WITH_DERIVED_PLAIN);

        MailSendRecord record = invokeBuildRecord(dto);
        Assertions.assertEquals(BodyMode.HTML_WITH_DERIVED_PLAIN, record.getBodyMode());
        Assertions.assertEquals("<p>Welcome <b>back</b></p>", record.getBodyHtml());
        Assertions.assertEquals("Welcome back", record.getBodyText(),
                "Derived plain is persisted verbatim too — no re-derivation at retry");

        SendMailDTO rebuilt = invokeRebuildDTO(record);
        Assertions.assertEquals(BodyMode.HTML_WITH_DERIVED_PLAIN, rebuilt.getBodyMode());
        Assertions.assertEquals("<p>Welcome <b>back</b></p>", rebuilt.getHtmlBody());
        Assertions.assertEquals("Welcome back", rebuilt.getTextBody());
    }

    @Test
    void replyToFallbackChainPersistsConfigDefaultWhenDtoEmpty() throws Exception {
        // dto carries no replyTo (caller didn't override, no template path).
        // buildRecord must apply the third tier (config.replyToAddress) and
        // persist that on the record so retry replays the same value.
        SendMailDTO dto = new SendMailDTO();
        dto.setHtmlBody("<p>X</p>");
        // dto.replyTo intentionally null

        MailSendServerConfig config = new MailSendServerConfig();
        config.setId(1L);
        config.setFromAddress("noreply@example.com");
        config.setReplyToAddress("support@example.com");
        MailSendRecord record = sendAndCapture(dto, config);

        Assertions.assertEquals("support@example.com", record.getReplyTo(),
                "Empty dto.replyTo must fall through to config.replyToAddress at persist time");

        SendMailDTO rebuilt = invokeRebuildDTO(record);
        Assertions.assertEquals("support@example.com", rebuilt.getReplyTo(),
                "Retry must replay the persisted Reply-To verbatim, not re-resolve from config");
    }

    @Test
    void replyToDtoOverrideWinsOverConfig() throws Exception {
        SendMailDTO dto = new SendMailDTO();
        dto.setHtmlBody("<p>X</p>");
        dto.setReplyTo("campaign-2024@example.com");

        MailSendServerConfig config = new MailSendServerConfig();
        config.setId(1L);
        config.setFromAddress("noreply@example.com");
        config.setReplyToAddress("support@example.com");
        MailSendRecord record = sendAndCapture(dto, config);

        Assertions.assertEquals("campaign-2024@example.com", record.getReplyTo(),
                "dto.replyTo override must take precedence over config default");
    }

    @Test
    void inferModeWhenDtoModeUnset() throws Exception {
        // Direct API caller didn't declare bodyMode; service infers from fields.
        // Both populated → AUTHORED (we treat the caller's plain as canonical content).
        SendMailDTO dto = new SendMailDTO();
        dto.setHtmlBody("<p>X</p>");
        dto.setTextBody("X");
        // dto.bodyMode left null

        MailSendRecord record = invokeBuildRecord(dto);
        Assertions.assertEquals(BodyMode.HTML_WITH_AUTHORED_PLAIN, record.getBodyMode());
    }

    // ---- helpers ----

    private MailSendRecord invokeBuildRecord(SendMailDTO dto) {
        MailSendServerConfig config = new MailSendServerConfig();
        config.setId(1L);
        config.setFromAddress("noreply@example.com");
        return sendAndCapture(dto, config);
    }

    @SuppressWarnings("unchecked")
    private MailSendRecord sendAndCapture(SendMailDTO dto, MailSendServerConfig config) {
        if (dto.getTo() == null) {
            dto.setTo(List.of("recipient@example.com"));
        }
        MailServerDispatcher dispatcher = mock(MailServerDispatcher.class);
        MailSendRecordService recordService = mock(MailSendRecordService.class);
        MailTemplateService templateService = mock(MailTemplateService.class);
        OutboxRecordWriter outboxRecordWriter = mock(OutboxRecordWriter.class);
        when(dispatcher.resolveSend()).thenReturn(config);
        when(outboxRecordWriter.persistAndEnqueue(
                any(), eq("MailSendRecord"), eq(TopicRoute.MAIL_SEND)))
                .thenAnswer(invocation -> ((Supplier<Long>) invocation.getArgument(0)).get());
        when(recordService.createOne(any(MailSendRecord.class))).thenReturn(1L);

        MailMessageHandler handler = new MailMessageHandler(
                dispatcher, recordService, templateService, outboxRecordWriter);
        handler.send(dto);

        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(recordService).createOne(captor.capture());
        return captor.getValue();
    }

    private SendMailDTO invokeRebuildDTO(MailSendRecord record) throws Exception {
        // rebuildDTO lives on the delivery side: the processor replays the
        // persisted record verbatim at consume time.
        MailDeliveryProcessor processor = new MailDeliveryProcessor();
        Method m = MailDeliveryProcessor.class.getDeclaredMethod("rebuildDTO", MailSendRecord.class);
        m.setAccessible(true);
        return (SendMailDTO) m.invoke(processor, record);
    }

}
