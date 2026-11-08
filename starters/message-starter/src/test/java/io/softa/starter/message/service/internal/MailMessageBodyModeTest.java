package io.softa.starter.message.service.internal;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;

import static org.mockito.Mockito.mock;

/**
 * Round-trip coverage for the four {@link BodyMode}s of caller-supplied bodies,
 * through acceptance ({@code accept}) → delivery ({@code rebuildDTO}). Both
 * bodies are persisted verbatim (option B unified storage) so retry replays
 * bit-for-bit, with no derivation at retry time. Template-rendered bodies are
 * covered by {@code MailDeliveryProcessorTest}.
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
        if (dto.getTo() == null) {
            dto.setTo(List.of("recipient@example.com"));
        }
        MailMessageHandler handler = new MailMessageHandler(mock(MailSendRecordService.class));
        return handler.accept(dto);
    }

    private SendMailDTO invokeRebuildDTO(MailSendRecord record) throws Exception {
        // rebuildDTO lives on the delivery side: the processor replays the
        // persisted record verbatim on every attempt.
        MailDeliveryProcessor processor = new MailDeliveryProcessor();
        Method m = MailDeliveryProcessor.class.getDeclaredMethod("rebuildDTO", MailSendRecord.class);
        m.setAccessible(true);
        return (SendMailDTO) m.invoke(processor, record);
    }

}
