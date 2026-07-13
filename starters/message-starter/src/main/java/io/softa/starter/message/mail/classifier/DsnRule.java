package io.softa.starter.message.mail.classifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.enums.EnhancedStatusCode;
import io.softa.starter.message.mail.enums.SmtpReplyCode;
import io.softa.starter.message.mail.support.BounceInfo;
import io.softa.starter.message.mail.support.MailClassification;

/**
 * RFC 3464 Delivery Status Notification parser — the authoritative signal
 * for a bounce. Matches on {@code Content-Type: multipart/report;
 * report-type=delivery-status} and extracts the structured
 * {@code message/delivery-status} part.
 * <p>
 * Runs before any heuristic rules because the extracted {@link BounceInfo}
 * carries Final-Recipient / SMTP reply / enhanced status — far richer than
 * what keyword matching can produce.
 */
@Slf4j
@Component
@Order(20)
public class DsnRule implements MailClassificationRule {

    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        String ct = message.getContentType();
        if (ct == null) return Optional.empty();
        String ctLower = ct.toLowerCase(Locale.ROOT);
        if (!ctLower.contains("multipart/report") || !ctLower.contains("delivery-status")) {
            return Optional.empty();
        }
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) return Optional.empty();

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String partCt = part.getContentType();
            if (partCt != null && partCt.toLowerCase(Locale.ROOT).contains("message/delivery-status")) {
                BounceInfo info = parseDsnFields(part.getContent().toString());
                info.setOriginalMessageId(MailClassificationSupport.extractOriginalMessageId(message));
                return Optional.of(MailClassification.bounce(info));
            }
        }
        return Optional.empty();
    }

    /**
     * Parse a {@code message/delivery-status} body into a {@link BounceInfo}.
     * Only handles the single-recipient fields — a DSN can technically hold
     * one block per recipient, but the upstream record model has one row per
     * recipient already so we flatten on purpose.
     */
    private BounceInfo parseDsnFields(String dsnText) {
        BounceInfo info = new BounceInfo();
        List<String> recipients = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(dsnText))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT).trim();
                if (lower.startsWith("final-recipient:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.toLowerCase(Locale.ROOT).startsWith("rfc822;")) {
                        value = value.substring(7).trim();
                    }
                    recipients.add(value);
                } else if (lower.startsWith("action:")) {
                    info.setAction(line.substring(line.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT));
                } else if (lower.startsWith("status:")) {
                    String status = line.substring(line.indexOf(':') + 1).trim();
                    info.setEnhancedStatusCode(status);
                    info.setPermanent(status.startsWith("5"));
                } else if (lower.startsWith("diagnostic-code:")) {
                    String diag = line.substring(line.indexOf(':') + 1).trim();
                    info.setDiagnosticMessage(diag);
                    SmtpReplyCode.fromContent(diag).ifPresent(c -> info.setSmtpReplyCode(c.getCode()));
                }
            }
        } catch (IOException e) {
            log.error("DsnRule: error parsing DSN body: {}", e.getMessage(), e);
        }

        info.setFailedRecipients(recipients);
        // In case a DSN omitted the explicit Status: line but has an enhanced code in diag.
        if (info.getEnhancedStatusCode() == null && info.getDiagnosticMessage() != null) {
            EnhancedStatusCode.extractRaw(info.getDiagnosticMessage()).ifPresent(info::setEnhancedStatusCode);
        }
        return info;
    }
}
