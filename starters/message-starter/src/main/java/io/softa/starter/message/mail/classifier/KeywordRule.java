package io.softa.starter.message.mail.classifier;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Last-resort bounce detection by subject / body keywords. Covers providers
 * that ship non-RFC NDRs (some legacy Exchange setups, Chinese ISPs) and
 * one-off "Undeliverable:" auto-replies from corporate gateways.
 * <p>
 * Runs last — this is the noisiest signal and earlier rules should have
 * classified anything structured. If you add a provider with a known NDR
 * shape, prefer a new ordered rule (@Order(25)) over extending this list.
 */
@Component
@Order(99)
public class KeywordRule implements MailClassificationRule {

    private static final String[] BOUNCE_KEYWORDS = {
            "Delivery Status Notification", "Undeliverable", "Mail delivery failed",
            "Failed to deliver", "未送达", "拒绝接收", "rejected",
            "Returned to sender", "Undelivered Mail"
    };

    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        String subject = message.getSubject();
        String body = MailClassificationSupport.extractPlainText(message);
        String combined = ((subject != null ? subject : "") + " " + body).toLowerCase(Locale.ROOT);
        boolean hit = Arrays.stream(BOUNCE_KEYWORDS)
                .anyMatch(kw -> combined.contains(kw.toLowerCase(Locale.ROOT)));
        if (!hit) return Optional.empty();

        return Optional.of(MailClassificationSupport.heuristicBounce(message));
    }
}
