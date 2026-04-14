package io.softa.starter.message.mail.support;

import io.softa.starter.message.mail.enums.EnhancedStatusCode;
import io.softa.starter.message.mail.enums.SmtpReplyCode;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies received emails into {@link MailClassification} using a three-layer strategy:
 * <ol>
 *   <li><b>Layer 1 — RFC 3464 DSN</b>: Parse {@code multipart/report; report-type=delivery-status}
 *       for structured bounce data.</li>
 *   <li><b>Layer 2 — From address</b>: Check if sender is MAILER-DAEMON or postmaster.</li>
 *   <li><b>Layer 3 — Content keywords</b>: Scan subject and body for bounce indicators.</li>
 * </ol>
 * <p>
 * Read receipt detection follows RFC 8098 (MDN):
 * Content-Type = {@code multipart/report; report-type=disposition-notification}.
 */
@Slf4j
@Component
public class MailClassifier {

    // ========== Read Receipt Detection (RFC 8098) ==========

    private static final String DISPOSITION_NOTIFICATION = "disposition-notification";

    /**
     * Detect MDN (Message Disposition Notification) read receipts.
     */
    public boolean isReadReceipt(MimeMessage msg) throws MessagingException {
        String ct = msg.getContentType();
        if (ct == null) return false;
        String ctLower = ct.toLowerCase();
        return ctLower.contains("multipart/report") && ctLower.contains(DISPOSITION_NOTIFICATION);
    }

    // ========== Bounce Detection ==========

    private static final String[] BOUNCE_SENDERS = {"mailer-daemon@", "postmaster@"};

    private static final String[] BOUNCE_KEYWORDS = {
            "Delivery Status Notification", "Undeliverable", "Mail delivery failed",
            "Failed to deliver", "未送达", "拒绝接收", "rejected",
            "Returned to sender", "Undelivered Mail"
    };

    /** Pattern to extract email addresses from bounce bodies. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /**
     * Classify a received email.
     *
     * @param msg the received MIME message
     * @return classification result with type, original message ID, and bounce details
     */
    public MailClassification classify(MimeMessage msg) {
        try {
            // --- Read receipt check first ---
            if (isReadReceipt(msg)) {
                return MailClassification.readReceipt(extractOriginalMessageId(msg));
            }

            // --- Layer 1: RFC 3464 DSN parsing (most reliable) ---
            Optional<BounceInfo> dsn = parseDsn(msg);
            if (dsn.isPresent()) {
                return MailClassification.bounce(dsn.get());
            }

            // --- Layer 2: From address pattern ---
            if (isBounceByFrom(msg)) {
                BounceInfo info = extractBounceInfoFromContent(msg);
                return MailClassification.bounce(info);
            }

            // --- Layer 3: Content keyword scan ---
            if (isBounceByContent(msg)) {
                BounceInfo info = extractBounceInfoFromContent(msg);
                return MailClassification.bounce(info);
            }

            return MailClassification.normal();
        } catch (Exception e) {
            log.warn("Error classifying email (Message-ID={}): {}", safeMessageId(msg), e.getMessage());
            return MailClassification.normal();
        }
    }

    // ========== Layer 1: RFC 3464 DSN ==========

    /**
     * Parse RFC 3464 Delivery Status Notification.
     * Looks for {@code multipart/report; report-type=delivery-status} and extracts
     * the {@code message/delivery-status} MIME part.
     */
    public Optional<BounceInfo> parseDsn(MimeMessage msg) throws Exception {
        String ct = msg.getContentType();
        if (ct == null) return Optional.empty();
        String ctLower = ct.toLowerCase();
        if (!ctLower.contains("multipart/report") || !ctLower.contains("delivery-status")) {
            return Optional.empty();
        }

        Object content = msg.getContent();
        if (!(content instanceof MimeMultipart multipart)) return Optional.empty();

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String partCt = part.getContentType();
            if (partCt != null && partCt.toLowerCase().contains("message/delivery-status")) {
                String dsnText = part.getContent().toString();
                BounceInfo info = parseDsnFields(dsnText);
                info.setOriginalMessageId(extractOriginalMessageId(msg));
                return Optional.of(info);
            }
        }
        return Optional.empty();
    }

    /**
     * Parse the structured fields from a {@code message/delivery-status} body.
     * <p>
     * Example DSN content:
     * <pre>
     * Reporting-MTA: dns;mail.example.com
     *
     * Final-Recipient: rfc822;user@example.com
     * Action: failed
     * Status: 5.1.1
     * Diagnostic-Code: smtp; 550 5.1.1 User unknown
     * </pre>
     */
    private BounceInfo parseDsnFields(String dsnText) {
        BounceInfo info = new BounceInfo();
        List<String> recipients = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(dsnText))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase().trim();
                if (lower.startsWith("final-recipient:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    // Remove "rfc822;" prefix
                    if (value.toLowerCase().startsWith("rfc822;")) {
                        value = value.substring(7).trim();
                    }
                    recipients.add(value);
                } else if (lower.startsWith("action:")) {
                    info.setAction(line.substring(line.indexOf(':') + 1).trim().toLowerCase());
                } else if (lower.startsWith("status:")) {
                    String status = line.substring(line.indexOf(':') + 1).trim();
                    info.setEnhancedStatusCode(status);
                    info.setPermanent(status.startsWith("5"));
                } else if (lower.startsWith("diagnostic-code:")) {
                    String diag = line.substring(line.indexOf(':') + 1).trim();
                    info.setDiagnosticMessage(diag);
                    // Extract SMTP reply code from diagnostic
                    SmtpReplyCode.fromContent(diag).ifPresent(c -> info.setSmtpReplyCode(c.getCode()));
                }
            }
        } catch (IOException e) {
            log.warn("Error parsing DSN fields: {}", e.getMessage());
        }

        info.setFailedRecipients(recipients);

        // If no SMTP code extracted from diagnostic, try from the enhanced status
        if (info.getSmtpReplyCode() == null && info.getEnhancedStatusCode() != null) {
            info.setPermanent(info.getEnhancedStatusCode().startsWith("5"));
        }

        return info;
    }

    // ========== Layer 2: From Address ==========

    private boolean isBounceByFrom(MimeMessage msg) throws MessagingException {
        if (msg.getFrom() == null || msg.getFrom().length == 0) return false;
        String from = ((InternetAddress) msg.getFrom()[0]).getAddress();
        if (from == null) return false;
        String fromLower = from.toLowerCase();
        return Arrays.stream(BOUNCE_SENDERS).anyMatch(fromLower::startsWith);
    }

    // ========== Layer 3: Content Keywords ==========

    private boolean isBounceByContent(MimeMessage msg) throws Exception {
        String subject = msg.getSubject();
        String combined = (subject != null ? subject : "") + " " + extractPlainText(msg);
        String lower = combined.toLowerCase();
        return Arrays.stream(BOUNCE_KEYWORDS).anyMatch(kw -> lower.contains(kw.toLowerCase()));
    }

    // ========== Shared Helpers ==========

    /**
     * Extract the original Message-ID from In-Reply-To or References headers.
     */
    public String extractOriginalMessageId(MimeMessage msg) throws MessagingException {
        String[] inReplyTo = msg.getHeader("In-Reply-To");
        if (inReplyTo != null && inReplyTo.length > 0) {
            return inReplyTo[0].trim();
        }
        String[] refs = msg.getHeader("References");
        if (refs != null && refs.length > 0) {
            // References may contain multiple IDs; the first is typically the original
            return refs[0].trim().split("\\s+")[0];
        }
        return null;
    }

    /**
     * Build a {@link BounceInfo} from email content when DSN parsing is not available.
     * Falls back to keyword/pattern matching on the body text.
     */
    private BounceInfo extractBounceInfoFromContent(MimeMessage msg) throws Exception {
        BounceInfo info = new BounceInfo();
        info.setOriginalMessageId(extractOriginalMessageId(msg));

        String body = extractPlainText(msg);
        String subject = msg.getSubject();
        String combined = (subject != null ? subject : "") + " " + body;

        // Extract SMTP reply code
        SmtpReplyCode.fromContent(combined).ifPresent(c -> {
            info.setSmtpReplyCode(c.getCode());
            info.setPermanent(c.isPermanent());
        });

        // Extract enhanced status code
        EnhancedStatusCode.extractRaw(combined).ifPresent(info::setEnhancedStatusCode);

        // Use combined text as diagnostic
        if (body != null && body.length() > 500) {
            info.setDiagnosticMessage(body.substring(0, 500));
        } else {
            info.setDiagnosticMessage(body);
        }

        // Extract failed recipient addresses from body
        List<String> recipients = new ArrayList<>();
        if (body != null) {
            Matcher m = EMAIL_PATTERN.matcher(body);
            while (m.find()) {
                String email = m.group();
                // Skip system addresses
                if (!email.toLowerCase().startsWith("mailer-daemon")
                        && !email.toLowerCase().startsWith("postmaster")) {
                    recipients.add(email);
                }
            }
        }
        info.setFailedRecipients(recipients);

        return info;
    }

    /**
     * Extract plain text from a MimeMessage, handling text/plain and multipart content.
     */
    private String extractPlainText(MimeMessage msg) throws Exception {
        Object content = msg.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof MimeMultipart multipart) {
            return extractTextFromMultipart(multipart);
        }
        return "";
    }

    private String extractTextFromMultipart(MimeMultipart multipart) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String ct = part.getContentType();
            if (ct != null && ct.toLowerCase().startsWith("text/plain")) {
                sb.append(part.getContent().toString());
            } else if (part.getContent() instanceof MimeMultipart nested) {
                sb.append(extractTextFromMultipart(nested));
            }
        }
        return sb.toString();
    }

    private String safeMessageId(MimeMessage msg) {
        try {
            return msg.getMessageID();
        } catch (MessagingException e) {
            return "unknown";
        }
    }
}
