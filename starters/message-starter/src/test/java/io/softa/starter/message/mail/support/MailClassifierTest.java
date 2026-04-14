package io.softa.starter.message.mail.support;

import java.util.Optional;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.message.mail.enums.ReceivedMailType;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailClassifierTest {

    private MailClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new MailClassifier();
    }

    // ========== Read Receipt Detection ==========

    @Test
    void isReadReceiptDetectsMdnContentType() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=disposition-notification; boundary=\"---\"");
        Assertions.assertTrue(classifier.isReadReceipt(msg));
    }

    @Test
    void isReadReceiptCaseInsensitive() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "Multipart/Report; Report-Type=Disposition-Notification");
        Assertions.assertTrue(classifier.isReadReceipt(msg));
    }

    @Test
    void isReadReceiptReturnsFalseForNormalMail() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain; charset=UTF-8");
        Assertions.assertFalse(classifier.isReadReceipt(msg));
    }

    @Test
    void isReadReceiptReturnsFalseForNullContentType() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(null);
        Assertions.assertFalse(classifier.isReadReceipt(msg));
    }

    @Test
    void isReadReceiptReturnsFalseForDsnContentType() throws Exception {
        // DSN (delivery-status) should NOT be detected as read receipt
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=delivery-status");
        Assertions.assertFalse(classifier.isReadReceipt(msg));
    }

    // ========== DSN Parsing (Layer 1) ==========

    @Test
    void parseDsnExtractsStructuredBounceInfo() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=delivery-status; boundary=\"---\"");

        // Build DSN content
        String dsnText = """
                Reporting-MTA: dns;mail.example.com

                Final-Recipient: rfc822;user@example.com
                Action: failed
                Status: 5.1.1
                Diagnostic-Code: smtp; 550 5.1.1 The email account does not exist
                """;

        BodyPart dsnPart = mock(BodyPart.class);
        when(dsnPart.getContentType()).thenReturn("message/delivery-status");
        when(dsnPart.getContent()).thenReturn(dsnText);

        MimeMultipart multipart = mock(MimeMultipart.class);
        when(multipart.getCount()).thenReturn(1);
        when(multipart.getBodyPart(0)).thenReturn(dsnPart);

        when(msg.getContent()).thenReturn(multipart);
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<original@example.com>"});

        Optional<BounceInfo> result = classifier.parseDsn(msg);

        Assertions.assertTrue(result.isPresent());
        BounceInfo info = result.get();
        Assertions.assertEquals("failed", info.getAction());
        Assertions.assertEquals("5.1.1", info.getEnhancedStatusCode());
        Assertions.assertEquals("550", info.getSmtpReplyCode());
        Assertions.assertTrue(info.isPermanent());
        Assertions.assertNotNull(info.getFailedRecipients());
        Assertions.assertEquals(1, info.getFailedRecipients().size());
        Assertions.assertEquals("user@example.com", info.getFailedRecipients().getFirst());
        Assertions.assertEquals("<original@example.com>", info.getOriginalMessageId());
    }

    @Test
    void parseDsnReturnsEmptyForNonDsnMessage() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");

        Optional<BounceInfo> result = classifier.parseDsn(msg);
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void parseDsnReturnsEmptyForNullContentType() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(null);

        Optional<BounceInfo> result = classifier.parseDsn(msg);
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void parseDsnHandlesTransientFailure() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=delivery-status");

        String dsnText = """
                Final-Recipient: rfc822;user@example.com
                Action: delayed
                Status: 4.2.2
                Diagnostic-Code: smtp; 452 4.2.2 Mailbox full
                """;

        BodyPart dsnPart = mock(BodyPart.class);
        when(dsnPart.getContentType()).thenReturn("message/delivery-status");
        when(dsnPart.getContent()).thenReturn(dsnText);

        MimeMultipart multipart = mock(MimeMultipart.class);
        when(multipart.getCount()).thenReturn(1);
        when(multipart.getBodyPart(0)).thenReturn(dsnPart);

        when(msg.getContent()).thenReturn(multipart);
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);

        Optional<BounceInfo> result = classifier.parseDsn(msg);

        Assertions.assertTrue(result.isPresent());
        BounceInfo info = result.get();
        Assertions.assertEquals("delayed", info.getAction());
        Assertions.assertEquals("4.2.2", info.getEnhancedStatusCode());
        Assertions.assertFalse(info.isPermanent());
    }

    // ========== extractOriginalMessageId ==========

    @Test
    void extractOriginalMessageIdFromInReplyTo() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<abc@example.com>"});

        String result = classifier.extractOriginalMessageId(msg);
        Assertions.assertEquals("<abc@example.com>", result);
    }

    @Test
    void extractOriginalMessageIdFromReferences() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(
                new String[]{"<first@example.com> <second@example.com>"});

        String result = classifier.extractOriginalMessageId(msg);
        Assertions.assertEquals("<first@example.com>", result);
    }

    @Test
    void extractOriginalMessageIdReturnsNullWhenNoHeaders() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);

        String result = classifier.extractOriginalMessageId(msg);
        Assertions.assertNull(result);
    }

    // ========== Classify — Integration ==========

    @Test
    void classifyDetectsReadReceipt() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=disposition-notification");
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<original@mail.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.READ_RECEIPT, result.getType());
        Assertions.assertEquals("<original@mail.com>", result.getOriginalMessageId());
        Assertions.assertNull(result.getBounceInfo());
    }

    @Test
    void classifyDetectsBounceByFrom() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getFrom()).thenReturn(new InternetAddress[]{new InternetAddress("MAILER-DAEMON@mail.example.com")});
        when(msg.getContent()).thenReturn("Your message could not be delivered to user@example.com. 550 5.1.1 User unknown");
        when(msg.getSubject()).thenReturn("Undeliverable: Test Email");
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<sent@example.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.BOUNCE, result.getType());
        Assertions.assertNotNull(result.getBounceInfo());
        Assertions.assertEquals("550", result.getBounceInfo().getSmtpReplyCode());
        Assertions.assertEquals("5.1.1", result.getBounceInfo().getEnhancedStatusCode());
    }

    @Test
    void classifyDetectsBounceByKeyword() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getFrom()).thenReturn(new InternetAddress[]{new InternetAddress("noreply@example.com")});
        when(msg.getSubject()).thenReturn("Delivery Status Notification (Failure)");
        when(msg.getContent()).thenReturn("The following message could not be delivered.");
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.BOUNCE, result.getType());
    }

    @Test
    void classifyReturnsNormalForRegularEmail() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/html; charset=UTF-8");
        when(msg.getFrom()).thenReturn(new InternetAddress[]{new InternetAddress("colleague@example.com")});
        when(msg.getSubject()).thenReturn("Meeting tomorrow");
        when(msg.getContent()).thenReturn("Hi, let's meet tomorrow at 10am.");

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.NORMAL, result.getType());
        Assertions.assertNull(result.getOriginalMessageId());
        Assertions.assertNull(result.getBounceInfo());
    }

    @Test
    void classifyReturnsNormalOnException() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenThrow(new jakarta.mail.MessagingException("Connection lost"));

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.NORMAL, result.getType());
    }

    @Test
    void classifyReadReceiptTakesPriorityOverBounce() throws Exception {
        // Edge case: content-type matches both read receipt and DSN patterns
        // Read receipt check happens first, so it should win
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=disposition-notification");
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<id@example.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.READ_RECEIPT, result.getType());
    }

    @Test
    void classifyBounceByPostmaster() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getFrom()).thenReturn(
                new InternetAddress[]{new InternetAddress("postmaster@mail.example.com")});
        when(msg.getContent()).thenReturn("Message undeliverable to someone@example.com");
        when(msg.getSubject()).thenReturn("Returned mail");
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.BOUNCE, result.getType());
    }
}
