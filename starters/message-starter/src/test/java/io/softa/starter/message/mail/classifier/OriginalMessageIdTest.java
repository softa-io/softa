package io.softa.starter.message.mail.classifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.enums.MailServerOwner;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.support.BounceInfo;
import io.softa.starter.message.mail.support.BounceReceiptLinker;
import io.softa.starter.message.mail.support.MailClassification;
import io.softa.starter.message.mail.support.MailClassifier;
import io.softa.starter.message.mail.support.MailMessageParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the original {@code Message-ID} of a bounce or a read receipt is found when the report
 * carries no {@code In-Reply-To} / {@code References} — RFC 3464 does not ask a DSN for either.
 * Built from real MIME text, not mocks: the parts' content types decide what Jakarta Mail hands
 * back, and a report part has no content handler, so its {@code getContent()} is a raw stream.
 */
class OriginalMessageIdTest {

    /** A Message-ID as JavaMail generates it on send and {@code markSent} stores it: mixed case kept. */
    private static final String ORIGINAL_ID = "<1960185137.7.1731300000000@App-Host.local>";

    private static final Session SESSION = Session.getInstance(new Properties());

    @Test
    void dsnIsLinkedThroughItsReturnedHeaders() throws Exception {
        MailClassification result = new DsnRule()
                .match(dsn("failed", "5.1.1", rfc822Headers(ORIGINAL_ID))).orElseThrow();

        BounceInfo info = result.getBounceInfo();
        assertEquals(ORIGINAL_ID, info.getOriginalMessageId());
        // The delivery-status part is parsed from its text, not from getContent().toString().
        assertEquals("failed", info.getAction());
        assertEquals("5.1.1", info.getEnhancedStatusCode());
        assertEquals("550", info.getSmtpReplyCode());
        assertEquals(List.of("nobody@example.org"), info.getFailedRecipients());
    }

    @Test
    void dsnIsLinkedThroughItsReturnedMessage() throws Exception {
        MailClassification result = new DsnRule()
                .match(dsn("failed", "5.1.1", rfc822Message(ORIGINAL_ID))).orElseThrow();

        assertEquals(ORIGINAL_ID, result.getOriginalMessageId());
    }

    @Test
    void delayedDsnIsKeptButNotLinked() throws Exception {
        // A warning that delivery is still being retried: linking it would mark the send
        // record bounced and failed while the mail may yet be delivered.
        MailClassification result = new DsnRule()
                .match(dsn("delayed", "4.4.7", rfc822Headers(ORIGINAL_ID))).orElseThrow();

        assertEquals(ReceivedMailType.BOUNCE, result.getType());
        assertEquals("delayed", result.getBounceInfo().getAction());
        assertNull(result.getOriginalMessageId());
    }

    @Test
    void readReceiptIsLinkedThroughItsOriginalMessageIdField() throws Exception {
        MailClassification result = new ReadReceiptRule().match(mdn(ORIGINAL_ID)).orElseThrow();

        assertEquals(ReceivedMailType.READ_RECEIPT, result.getType());
        assertEquals(ORIGINAL_ID, result.getOriginalMessageId());
    }

    @Test
    void originalMessageIdFieldWinsOverReturnedHeaders() throws Exception {
        MimeMessage receipt = mdn(ORIGINAL_ID, rfc822Headers("<someone-else@example.org>"));

        assertEquals(ORIGINAL_ID, MailClassificationSupport.extractOriginalMessageId(receipt));
    }

    @Test
    void nonStandardBounceIsLinkedThroughTheOriginalItAttaches() throws Exception {
        List<String> lines = new ArrayList<>(List.of(
                "From: MAILER-DAEMON@legacy.example.net",
                "To: sender@example.com",
                "Subject: Returned mail: User unknown",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"M\"",
                "",
                "--M",
                "Content-Type: text/plain; charset=us-ascii",
                "",
                "550 5.1.1 <nobody@example.org>... User unknown",
                "--M"));
        lines.addAll(List.of(rfc822Message(ORIGINAL_ID)));
        lines.addAll(List.of("--M--", ""));

        MailClassification result = new MailerDaemonRule().match(mime(lines)).orElseThrow();

        assertEquals(ReceivedMailType.BOUNCE, result.getType());
        assertEquals(ORIGINAL_ID, result.getOriginalMessageId());
    }

    @Test
    void replyHeadersAreStillReadFirst() throws Exception {
        MimeMessage bounce = dsn("failed", "5.1.1", rfc822Headers(ORIGINAL_ID));
        bounce.setHeader("In-Reply-To", "<reply-target@example.com>");

        assertEquals("<reply-target@example.com>", MailClassificationSupport.extractOriginalMessageId(bounce));
    }

    @Test
    void reportWithoutAnyOriginalYieldsNoId() throws Exception {
        assertNull(MailClassificationSupport.extractOriginalMessageId(dsn("failed", "5.1.1")));
    }

    @Test
    void messageIdIsReadInTheFormASendRecordStores() {
        assertEquals("<a.b@Host.Example>", MailClassificationSupport.normalizeMessageId("  <a.b@Host.Example>  "));
        assertEquals("<a@b>", MailClassificationSupport.normalizeMessageId("\r\n <a@b>"));
        assertEquals("<a@b>", MailClassificationSupport.normalizeMessageId("<a@b> (relayed)"));
        assertEquals("<a@b>", MailClassificationSupport.normalizeMessageId("<a@b> <c@d>"));
        // Brackets dropped by a gateway.
        assertEquals("<a@b>", MailClassificationSupport.normalizeMessageId("a@b"));
        assertNull(MailClassificationSupport.normalizeMessageId("<>"));
        assertNull(MailClassificationSupport.normalizeMessageId("your message of Monday"));
        assertNull(MailClassificationSupport.normalizeMessageId(null));
    }

    /**
     * End to end: the id read from a DSN is the one the send record holds — JavaMail's
     * {@code getMessageID()} after {@code saveChanges()}, stored verbatim by {@code markSent} —
     * so {@code findByMessageIds} finds the record and the bounce code is written to it.
     */
    @Test
    void bounceReachesTheSendRecordUnderTheIdStoredOnSend() throws Exception {
        MimeMessage original = new MimeMessage(SESSION);
        original.setFrom("sender@example.com");
        original.setRecipients(Message.RecipientType.TO, "nobody@example.org");
        original.setSubject("Hello");
        original.setText("Hello there.");
        original.saveChanges();
        String stored = original.getMessageID();

        List<String> returned = new ArrayList<>(List.of("Content-Type: text/rfc822-headers", ""));
        returned.addAll(Collections.list(original.getAllHeaderLines()));
        returned.add("");
        MailReceiveRecord received = parser().parse(
                dsn("failed", "5.1.1", returned.toArray(String[]::new)), receiveConfig(), "INBOX");

        MailSendRecordService sendRecords = mock(MailSendRecordService.class);
        MailSendRecord sent = new MailSendRecord();
        sent.setId(42L);
        sent.setVersion(3L);
        sent.setMessageId(stored);
        when(sendRecords.findByMessageIds(List.of(stored), MailServerOwner.TENANT)).thenReturn(Map.of(stored, sent));
        BounceReceiptLinker linker = new BounceReceiptLinker();
        ReflectionTestUtils.setField(linker, "sendRecordService", sendRecords);

        linker.link(List.of(received), MailServerOwner.TENANT);

        verify(sendRecords).markBounced(42L, 3L, "550 5.1.1");
    }

    private static MailMessageParser parser() {
        MailClassifier classifier = new MailClassifier();
        ReflectionTestUtils.setField(classifier, "rules", List.of(
                new ReadReceiptRule(), new DsnRule(), new MailerDaemonRule(), new KeywordRule()));
        ReflectionTestUtils.setField(classifier, "flagDetectors", List.of());
        MailMessageParser parser = new MailMessageParser();
        ReflectionTestUtils.setField(parser, "mailClassifier", classifier);
        return parser;
    }

    private static MailReceiveServerConfig receiveConfig() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setId(1L);
        return config;
    }

    /** An RFC 3464 DSN without In-Reply-To / References; {@code thirdPart} is the returned original, if any. */
    private static MimeMessage dsn(String action, String status, String... thirdPart) throws MessagingException {
        List<String> lines = new ArrayList<>(List.of(
                "From: MAILER-DAEMON@mx.example.net",
                "To: sender@example.com",
                "Subject: Undelivered Mail Returned to Sender",
                "Message-ID: <bounce-1@mx.example.net>",
                "MIME-Version: 1.0",
                "Content-Type: multipart/report; report-type=delivery-status; boundary=\"B\"",
                "",
                "--B",
                "Content-Type: text/plain; charset=us-ascii",
                "",
                "Your message could not be delivered.",
                "--B",
                "Content-Type: message/delivery-status",
                "",
                "Reporting-MTA: dns; mx.example.net",
                "",
                "Final-Recipient: rfc822; nobody@example.org",
                "Action: " + action,
                "Status: " + status,
                "Diagnostic-Code: smtp; 550 5.1.1 User unknown",
                ""));
        if (thirdPart.length > 0) {
            lines.add("--B");
            lines.addAll(List.of(thirdPart));
        }
        lines.addAll(List.of("--B--", ""));
        return mime(lines);
    }

    /** An RFC 8098 read receipt without In-Reply-To / References. */
    private static MimeMessage mdn(String originalId, String... thirdPart) throws MessagingException {
        List<String> lines = new ArrayList<>(List.of(
                "From: recipient@example.org",
                "To: sender@example.com",
                "Subject: Read: Hello",
                "MIME-Version: 1.0",
                "Content-Type: multipart/report; report-type=disposition-notification; boundary=\"R\"",
                "",
                "--R",
                "Content-Type: text/plain; charset=us-ascii",
                "",
                "The message was displayed.",
                "--R",
                "Content-Type: message/disposition-notification",
                "",
                "Reporting-UA: pc.example.org; Mailer 1.0",
                "Final-Recipient: rfc822; recipient@example.org",
                "Original-Message-ID: " + originalId,
                "Disposition: manual-action/MDN-sent-manually; displayed",
                ""));
        if (thirdPart.length > 0) {
            lines.add("--R");
            lines.addAll(List.of(thirdPart));
        }
        lines.addAll(List.of("--R--", ""));
        return mime(lines);
    }

    private static String[] rfc822Headers(String messageId) {
        return new String[]{
                "Content-Type: text/rfc822-headers",
                "",
                "From: sender@example.com",
                "To: nobody@example.org",
                "Subject: Hello",
                "Message-ID: " + messageId,
                ""};
    }

    private static String[] rfc822Message(String messageId) {
        return new String[]{
                "Content-Type: message/rfc822",
                "",
                "From: sender@example.com",
                "To: nobody@example.org",
                "Subject: Hello",
                "Message-ID: " + messageId,
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=us-ascii",
                "",
                "Hello there.",
                ""};
    }

    private static MimeMessage mime(List<String> lines) throws MessagingException {
        return new MimeMessage(SESSION, new ByteArrayInputStream(
                String.join("\r\n", lines).getBytes(StandardCharsets.US_ASCII)));
    }
}
