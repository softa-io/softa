package io.softa.starter.message.mail.support;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.message.mail.classifier.*;
import io.softa.starter.message.mail.enums.ReceivedMailType;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers both the individual rule classes and the chain dispatcher.
 * <p>
 * Rule ordering matches production {@link org.springframework.core.annotation.Order}
 * annotations — ReadReceipt → Dsn → MailerDaemon → Keyword.
 */
class MailClassifierTest {

    private MailClassifier classifier;

    @BeforeEach
    void setUp() throws Exception {
        classifier = new MailClassifier();
        // Phase-1 chain: primary mutually-exclusive content type, by @Order.
        List<MailClassificationRule> rules = List.of(
                new ReadReceiptRule(),       // @Order(10)
                new CalendarInviteRule(),    // @Order(15)
                new AutoReplyRule(),         // @Order(18)
                new DsnRule(),               // @Order(20)
                new MailerDaemonRule(),      // @Order(30)
                new KeywordRule());          // @Order(99)
        Field rulesField = MailClassifier.class.getDeclaredField("rules");
        rulesField.setAccessible(true);
        rulesField.set(classifier, rules);

        // Phase-2 detectors: orthogonal flags applied independently afterwards.
        List<MailFlagDetector> flagDetectors = List.of(
                new MailingListFlagDetector(),
                new EncryptedFlagDetector(),
                new SpamFlagDetector());
        Field flagsField = MailClassifier.class.getDeclaredField("flagDetectors");
        flagsField.setAccessible(true);
        flagsField.set(classifier, flagDetectors);
    }

    // ========== ReadReceiptRule ==========

    @Test
    void readReceiptRuleMatchesMdnContentType() throws Exception {
        ReadReceiptRule rule = new ReadReceiptRule();
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=disposition-notification; boundary=\"---\"");
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<id@example.com>"});
        Optional<MailClassification> result = rule.match(msg);
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(ReceivedMailType.READ_RECEIPT, result.get().getType());
    }

    @Test
    void readReceiptRuleIgnoresDsnContentType() throws Exception {
        ReadReceiptRule rule = new ReadReceiptRule();
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("multipart/report; report-type=delivery-status");
        Assertions.assertTrue(rule.match(msg).isEmpty());
    }

    @Test
    void readReceiptRuleIgnoresNullContentType() throws Exception {
        ReadReceiptRule rule = new ReadReceiptRule();
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(null);
        Assertions.assertTrue(rule.match(msg).isEmpty());
    }

    // ========== DsnRule ==========

    @Test
    void dsnRuleExtractsStructuredBounceInfo() throws Exception {
        DsnRule rule = new DsnRule();
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/report; report-type=delivery-status; boundary=\"---\"");

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

        Optional<MailClassification> result = rule.match(msg);
        Assertions.assertTrue(result.isPresent());
        BounceInfo info = result.get().getBounceInfo();
        Assertions.assertNotNull(info);
        Assertions.assertEquals("failed", info.getAction());
        Assertions.assertEquals("5.1.1", info.getEnhancedStatusCode());
        Assertions.assertEquals("550", info.getSmtpReplyCode());
        Assertions.assertTrue(info.isPermanent());
        Assertions.assertEquals(1, info.getFailedRecipients().size());
        Assertions.assertEquals("user@example.com", info.getFailedRecipients().getFirst());
        Assertions.assertEquals("<original@example.com>", info.getOriginalMessageId());
    }

    @Test
    void dsnRuleSkipsNonDsn() throws Exception {
        DsnRule rule = new DsnRule();
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        Assertions.assertTrue(rule.match(msg).isEmpty());
    }

    @Test
    void dsnRuleHandlesTransientFailure() throws Exception {
        DsnRule rule = new DsnRule();
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("multipart/report; report-type=delivery-status");

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

        Optional<MailClassification> result = rule.match(msg);
        Assertions.assertTrue(result.isPresent());
        BounceInfo info = result.get().getBounceInfo();
        Assertions.assertEquals("delayed", info.getAction());
        Assertions.assertEquals("4.2.2", info.getEnhancedStatusCode());
        Assertions.assertFalse(info.isPermanent());
    }

    // ========== MailClassificationSupport ==========

    @Test
    void extractOriginalMessageIdFromInReplyTo() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getHeader("In-Reply-To")).thenReturn(new String[]{"<abc@example.com>"});
        Assertions.assertEquals("<abc@example.com>",
                MailClassificationSupport.extractOriginalMessageId(msg));
    }

    @Test
    void extractOriginalMessageIdFromReferencesFirstEntryWins() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(
                new String[]{"<first@example.com> <second@example.com>"});
        Assertions.assertEquals("<first@example.com>",
                MailClassificationSupport.extractOriginalMessageId(msg));
    }

    @Test
    void extractOriginalMessageIdNullWhenMissing() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);
        Assertions.assertNull(MailClassificationSupport.extractOriginalMessageId(msg));
    }

    // ========== Classifier chain integration ==========

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
    void classifyReturnsUnknownWhenRulesThrowWithoutPositiveMatch() throws Exception {
        // When no rule produces a positive match AND at least one rule threw
        // during scan, the result is UNKNOWN — distinguishes a genuine clean
        // "regular email" verdict (NORMAL) from a classifier failure that we
        // shouldn't silently relabel as Normal.
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenThrow(new MessagingException("Connection lost"));

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.UNKNOWN, result.getType());
    }

    @Test
    void classifyReadReceiptTakesPriorityOverBounce() throws Exception {
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

    // ========== CalendarInviteRule ==========

    @Test
    void classifyDetectsCalendarInviteByTopLevelContentType() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.isMimeType("text/calendar")).thenReturn(true);

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.CALENDAR_INVITE, result.getType());
    }

    @Test
    void classifyDetectsCalendarInviteInsideMultipart() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.isMimeType("text/calendar")).thenReturn(false);
        when(msg.isMimeType("application/ics")).thenReturn(false);
        when(msg.isMimeType("multipart/*")).thenReturn(true);

        MimeMultipart mp = mock(MimeMultipart.class);
        BodyPart calPart = mock(BodyPart.class);
        when(calPart.isMimeType("text/calendar")).thenReturn(true);
        when(mp.getCount()).thenReturn(1);
        when(mp.getBodyPart(0)).thenReturn(calPart);
        when(msg.getContent()).thenReturn(mp);

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.CALENDAR_INVITE, result.getType());
    }

    // ========== AutoReplyRule ==========

    @Test
    void classifyDetectsAutoReplyByAutoSubmittedHeader() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(new String[]{"auto-replied"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.AUTO_REPLY, result.getType());
    }

    @Test
    void autoReplyRuleIgnoresAutoSubmittedNo() throws Exception {
        // RFC 3834: "Auto-Submitted: no" explicitly marks a human-authored reply
        // and must NOT be treated as automated.
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(new String[]{"no"});
        when(msg.getFrom()).thenReturn(new InternetAddress[]{new InternetAddress("a@b.com")});
        when(msg.getContent()).thenReturn("hello");
        when(msg.getSubject()).thenReturn("regular message");

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.NORMAL, result.getType());
    }

    @Test
    void classifyDetectsAutoReplyByExchangeMarker() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(null);
        when(msg.getHeader("X-Auto-Response-Suppress")).thenReturn(new String[]{"All"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.AUTO_REPLY, result.getType());
    }

    // ========== MailingListFlagDetector — orthogonal flag, not primary type ==========

    @Test
    void mailingListFlagSetByListIdHeader() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(null);
        when(msg.getHeader("X-Auto-Response-Suppress")).thenReturn(null);
        when(msg.getHeader("Precedence")).thenReturn(null);
        when(msg.getHeader("List-Id")).thenReturn(
                new String[]{"<dev.mailing-list.example.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.NORMAL, result.getType());
        Assertions.assertTrue(result.isMailingList());
    }

    @Test
    void mailingListFlagSetByListUnsubscribeHeader() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(null);
        when(msg.getHeader("X-Auto-Response-Suppress")).thenReturn(null);
        when(msg.getHeader("Precedence")).thenReturn(null);
        when(msg.getHeader("List-Id")).thenReturn(null);
        when(msg.getHeader("List-Unsubscribe")).thenReturn(
                new String[]{"<mailto:unsubscribe@example.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isMailingList());
    }

    @Test
    void mailingListFlagSetByPrecedenceBulk() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(null);
        when(msg.getHeader("X-Auto-Response-Suppress")).thenReturn(null);
        when(msg.getHeader("List-Id")).thenReturn(null);
        when(msg.getHeader("List-Unsubscribe")).thenReturn(null);
        when(msg.getHeader("Precedence")).thenReturn(new String[]{"bulk"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isMailingList());
    }

    // ========== Orthogonality: primary type and flags coexist ==========

    @Test
    void autoReplyAndMailingListAreIndependent() throws Exception {
        // Vacation responder distributed via a list-management system carries
        // both Auto-Submitted and List-Unsubscribe. They are now orthogonal:
        // primary type = AUTO_REPLY, mailingList flag = true.
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("Auto-Submitted")).thenReturn(new String[]{"auto-replied"});
        when(msg.getHeader("List-Unsubscribe")).thenReturn(
                new String[]{"<mailto:unsub@example.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.AUTO_REPLY, result.getType());
        Assertions.assertTrue(result.isMailingList());
    }

    @Test
    void calendarInviteFromMailingListPreservesBothSignals() throws Exception {
        // A list announcing an event sends an iCalendar invite — both signals matter:
        // primary type CALENDAR_INVITE so UI shows RSVP, mailingList flag so the
        // inbox can route it to "subscriptions" alongside other list traffic.
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.isMimeType("text/calendar")).thenReturn(true);
        when(msg.getHeader("List-Id")).thenReturn(new String[]{"<events.list.example.com>"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.CALENDAR_INVITE, result.getType());
        Assertions.assertTrue(result.isMailingList());
    }

    // ========== EncryptedFlagDetector ==========

    @Test
    void encryptedFlagSetByPgpMimeContentType() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"x\"");

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isEncrypted());
    }

    @Test
    void encryptedFlagSetBySmimeEnvelopedData() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"");

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isEncrypted());
    }

    @Test
    void encryptedFlagNotSetForSmimeSignedOnly() throws Exception {
        // smime-type=signed-data means the message is signed but not encrypted —
        // the body is plaintext and the recipient does NOT need a key to read it.
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn(
                "application/pkcs7-mime; smime-type=signed-data; name=\"smime.p7s\"");

        MailClassification result = classifier.classify(msg);
        Assertions.assertFalse(result.isEncrypted());
    }

    // ========== SpamFlagDetector ==========

    @Test
    void spamFlagSetByXSpamFlagYes() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("X-Spam-Flag")).thenReturn(new String[]{"YES"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isSpam());
    }

    @Test
    void spamFlagSetByXSpamStatusYes() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("X-Spam-Flag")).thenReturn(null);
        when(msg.getHeader("X-Spam-Status")).thenReturn(
                new String[]{"Yes, score=8.4 required=5.0"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isSpam());
    }

    @Test
    void spamFlagSetByExchangeSclThreshold() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("X-Spam-Flag")).thenReturn(null);
        when(msg.getHeader("X-Spam-Status")).thenReturn(null);
        when(msg.getHeader("X-MS-Exchange-Organization-SCL")).thenReturn(new String[]{"7"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertTrue(result.isSpam());
    }

    @Test
    void spamFlagNotSetForXSpamFlagNo() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("X-Spam-Flag")).thenReturn(new String[]{"NO"});
        when(msg.getHeader("X-Spam-Status")).thenReturn(null);
        when(msg.getHeader("X-MS-Exchange-Organization-SCL")).thenReturn(null);

        MailClassification result = classifier.classify(msg);
        Assertions.assertFalse(result.isSpam());
    }

    @Test
    void spamFlagNotSetForLowExchangeScl() throws Exception {
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getHeader("X-Spam-Flag")).thenReturn(null);
        when(msg.getHeader("X-Spam-Status")).thenReturn(null);
        when(msg.getHeader("X-MS-Exchange-Organization-SCL")).thenReturn(new String[]{"3"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertFalse(result.isSpam());
    }

    // ========== Backscatter spam: BOUNCE-shaped + spam flag ==========

    @Test
    void backscatterSpamPreservesBounceTypeAndSpamFlag() throws Exception {
        // A spam filter may flag a bounce email (backscatter) — the bounce
        // structure is real (someone forged our sender), but our spam filter
        // also tagged it. Both signals should survive.
        MimeMessage msg = mock(MimeMessage.class);
        when(msg.getContentType()).thenReturn("text/plain");
        when(msg.getFrom()).thenReturn(
                new InternetAddress[]{new InternetAddress("postmaster@evil.example.com")});
        when(msg.getContent()).thenReturn("Mail delivery failed for spam-target@here.example.com");
        when(msg.getSubject()).thenReturn("Returned mail");
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);
        when(msg.getHeader("X-Spam-Flag")).thenReturn(new String[]{"YES"});

        MailClassification result = classifier.classify(msg);
        Assertions.assertEquals(ReceivedMailType.BOUNCE, result.getType());
        Assertions.assertTrue(result.isSpam());
    }
}
