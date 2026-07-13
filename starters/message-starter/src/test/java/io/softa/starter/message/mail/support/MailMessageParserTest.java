package io.softa.starter.message.mail.support;

import java.util.Properties;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.enums.TruncationReason;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct unit tests for {@link MailMessageParser} — exercising the message →
 * {@link MailReceiveRecord} conversion through the public {@code parse(...)}
 * entry point (no reflection). {@code fileService} is left null so attachment
 * upload is a silent no-op; the classifier is stubbed to NORMAL.
 */
class MailMessageParserTest {

    private MailMessageParser parser;
    private MessageProperties messageProperties;

    @BeforeEach
    void setUp() {
        parser = new MailMessageParser();
        MailClassifier mailClassifier = mock(MailClassifier.class);
        MailClassification normal = new MailClassification();
        normal.setType(ReceivedMailType.NORMAL);
        when(mailClassifier.classify(any())).thenReturn(normal);
        messageProperties = new MessageProperties();
        ReflectionTestUtils.setField(parser, "mailClassifier", mailClassifier);
        ReflectionTestUtils.setField(parser, "messageProperties", messageProperties);
        // fileService null by default (optional dependency)
    }

    @Test
    void plainOnlySetsBodyModePlain() throws Exception {
        MimeMessage msg = msg();
        msg.setText("Hello, world.", "UTF-8");
        msg.setHeader("Message-ID", "<plain@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertEquals(BodyMode.PLAIN, r.getBodyMode());
        Assertions.assertEquals("Hello, world.", r.getBodyText());
        Assertions.assertNull(r.getBodyHtml());
        Assertions.assertNull(r.getAttachments());
        Assertions.assertEquals(ReceivedMailType.NORMAL, r.getMailType());
    }

    @Test
    void htmlOnlyDerivesPlainAtWriteTime() throws Exception {
        MimeMessage msg = msg();
        msg.setContent("<p>Rich</p>", "text/html; charset=UTF-8");
        msg.setHeader("Message-ID", "<html@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        // bodyMode captured BEFORE derivation, so HTML-only still reads as HTML.
        Assertions.assertEquals(BodyMode.HTML, r.getBodyMode());
        Assertions.assertEquals("<p>Rich</p>", r.getBodyHtml());
        Assertions.assertNotNull(r.getBodyText(), "plain derived at write time for O(1) reads");
        Assertions.assertFalse(r.getBodyText().contains("<p>"), "derived plain must strip markup");
    }

    @Test
    void multipartAlternativeKeepsSenderPlain() throws Exception {
        MimeMessage msg = msg();
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("plain version", "UTF-8");
        alt.addBodyPart(textPart);
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>html version</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(htmlPart);
        msg.setContent(alt);
        msg.setHeader("Message-ID", "<alt@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertEquals(BodyMode.HTML_WITH_AUTHORED_PLAIN, r.getBodyMode());
        Assertions.assertEquals("plain version", r.getBodyText());
        Assertions.assertEquals("<p>html version</p>", r.getBodyHtml());
    }

    @Test
    void multipartAlternativeOrderInsensitive() throws Exception {
        MimeMessage msg = msg();
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>html first</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(htmlPart);
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("plain second", "UTF-8");
        alt.addBodyPart(textPart);
        msg.setContent(alt);
        msg.setHeader("Message-ID", "<alt2@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertEquals("plain second", r.getBodyText());
        Assertions.assertEquals("<p>html first</p>", r.getBodyHtml());
    }

    @Test
    void mixedAttachmentSkippedWhenNoFileService() throws Exception {
        MimeMessage msg = msg();
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setText("body text", "UTF-8");
        mixed.addBodyPart(bodyPart);
        MimeBodyPart att = new MimeBodyPart();
        att.setText("ignored attachment payload");
        att.setFileName("report.pdf");
        att.setDisposition(Part.ATTACHMENT);
        mixed.addBodyPart(att);
        msg.setContent(mixed);
        msg.setHeader("Message-ID", "<mixed@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertEquals("body text", r.getBodyText());
        Assertions.assertNull(r.getAttachments(),
                "fileService is null in this unit test; attachment must be skipped silently");
    }

    @Test
    void attachmentsOnlyLeavesBodiesAndModeNull() throws Exception {
        MimeMessage msg = msg();
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart att = new MimeBodyPart();
        att.setText("payload");
        att.setFileName("doc.bin");
        att.setDisposition(Part.ATTACHMENT);
        mixed.addBodyPart(att);
        msg.setContent(mixed);
        msg.setHeader("Message-ID", "<attonly@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertNull(r.getBodyText());
        Assertions.assertNull(r.getBodyHtml());
        Assertions.assertNull(r.getBodyMode());
    }

    @Test
    void mimeDepthExceededTruncates() throws Exception {
        messageProperties.getMail().getFetch().setMaxMimeDepth(10);
        MimeMessage msg = msg();
        MimeMultipart current = new MimeMultipart("mixed");
        MimeMultipart outer = current;
        for (int i = 0; i < 12; i++) {
            MimeBodyPart wrap = new MimeBodyPart();
            MimeMultipart inner = new MimeMultipart("mixed");
            wrap.setContent(inner);
            current.addBodyPart(wrap);
            current = inner;
        }
        MimeBodyPart leaf = new MimeBodyPart();
        leaf.setText("deep", "UTF-8");
        current.addBodyPart(leaf);
        msg.setContent(outer);
        msg.setHeader("Message-ID", "<deep@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertEquals(TruncationReason.MimeDepthExceeded.name(), r.getTruncationReason());
        Assertions.assertNull(r.getBodyText());
        Assertions.assertNull(r.getBodyHtml());
    }

    @Test
    void mimePartsExceededTruncates() throws Exception {
        messageProperties.getMail().getFetch().setMaxMimeParts(10);
        MimeMessage msg = msg();
        MimeMultipart mixed = new MimeMultipart("mixed");
        for (int i = 0; i < 50; i++) {
            MimeBodyPart bp = new MimeBodyPart();
            bp.setText("p" + i, "UTF-8");
            mixed.addBodyPart(bp);
        }
        msg.setContent(mixed);
        msg.setHeader("Message-ID", "<many@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertEquals(TruncationReason.MimePartsExceeded.name(), r.getTruncationReason());
    }

    @Test
    void withinLimitsSucceeds() throws Exception {
        messageProperties.getMail().getFetch().setMaxMimeDepth(10);
        messageProperties.getMail().getFetch().setMaxMimeParts(100);
        MimeMessage msg = msg();
        msg.setText("ok", "UTF-8");
        msg.setHeader("Message-ID", "<ok@example.com>");
        msg.saveChanges();

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertNull(r.getTruncationReason());
        Assertions.assertEquals("ok", r.getBodyText());
    }

    @Test
    void oversizedMessageProducesEnvelopeOnly() throws Exception {
        MimeMessage delegate = msg();
        delegate.setSubject("subj");
        delegate.setFrom("alice@example.com");
        delegate.setRecipients(Message.RecipientType.TO, "bob@example.com");
        delegate.setText("body", "UTF-8");
        delegate.setHeader("Message-ID", "<oversized@example.com>");
        delegate.saveChanges();
        // Simulate a >maxMessageSize message by overriding getSize().
        MimeMessage oversized = new MimeMessage(delegate) {
            @Override public int getSize() { return Integer.MAX_VALUE; }
        };

        MailReceiveRecord r = parser.parse(oversized, config(), "INBOX");
        Assertions.assertEquals(TruncationReason.BodyTooLarge.name(), r.getTruncationReason());
        Assertions.assertNull(r.getBodyText());
        Assertions.assertNull(r.getBodyHtml());
        Assertions.assertNull(r.getAttachments());
        Assertions.assertNull(r.getBodyMode(), "envelope-only records have no body");
        Assertions.assertEquals("alice@example.com", r.getFromAddress());
    }

    @Test
    void missingMessageIdGetsSyntheticKey() throws Exception {
        MimeMessage msg = msg();
        msg.setText("no id", "UTF-8");
        msg.saveChanges();
        // saveChanges() auto-generates a Message-ID; drop it to exercise the
        // synthetic-key fallback for messages that genuinely lack the header.
        msg.removeHeader("Message-ID");

        MailReceiveRecord r = parser.parse(msg, config(), "INBOX");
        Assertions.assertNotNull(r.getMessageId());
        Assertions.assertTrue(r.getMessageId().startsWith("synthetic:"));
    }

    private static MailReceiveServerConfig config() {
        MailReceiveServerConfig c = new MailReceiveServerConfig();
        c.setId(1L);
        return c;
    }

    private static MimeMessage msg() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }
}
