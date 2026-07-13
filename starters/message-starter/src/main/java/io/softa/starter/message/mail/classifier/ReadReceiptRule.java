package io.softa.starter.message.mail.classifier;

import java.util.Locale;
import java.util.Optional;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Detects RFC 8098 Message Disposition Notifications (read receipts).
 * <p>
 * Matched by {@code Content-Type: multipart/report; report-type=disposition-notification}.
 * Runs first because the check is a header string scan — cheap, unambiguous,
 * and we don't want a keyword-based bounce rule to accidentally reclassify
 * a read receipt.
 */
@Component
@Order(10)
public class ReadReceiptRule implements MailClassificationRule {

    private static final String DISPOSITION_NOTIFICATION = "disposition-notification";

    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        String ct = message.getContentType();
        if (ct == null) return Optional.empty();
        String ctLower = ct.toLowerCase(Locale.ROOT);
        if (ctLower.contains("multipart/report") && ctLower.contains(DISPOSITION_NOTIFICATION)) {
            String originalId = MailClassificationSupport.extractOriginalMessageId(message);
            return Optional.of(MailClassification.readReceipt(originalId));
        }
        return Optional.empty();
    }
}
