package io.softa.starter.message.mail.classifier;

import java.util.Locale;
import java.util.Optional;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Detects automated replies (out-of-office, vacation responders) per
 * RFC 3834. The standardized signal is the {@code Auto-Submitted} header —
 * any value other than {@code no} means the message was not produced by a
 * person (e.g. {@code auto-replied}, {@code auto-generated}, {@code auto-notified}).
 * <p>
 * Two legacy fallbacks: Microsoft's {@code X-Auto-Response-Suppress} marker on
 * Exchange auto-replies, and the older {@code Precedence: auto_reply} convention.
 * <p>
 * Runs after specialty Content-Type rules (read-receipt, calendar) and before
 * bounce / mailing-list — DSN bounces have their own structured shape, but a
 * sloppy bounce wrapper that also adds {@code Auto-Submitted: auto-generated}
 * should still be reachable by {@link DsnRule} which is ordered ahead of this.
 */
@Component
@Order(18)
public class AutoReplyRule implements MailClassificationRule {

    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        String[] autoSubmitted = message.getHeader("Auto-Submitted");
        if (autoSubmitted != null && autoSubmitted.length > 0) {
            String value = autoSubmitted[0].trim().toLowerCase(Locale.ROOT);
            // RFC 3834: the only "not auto" value is "no" (case-insensitive).
            if (!value.isEmpty() && !value.startsWith("no")) {
                return Optional.of(MailClassification.autoReply());
            }
        }
        if (message.getHeader("X-Auto-Response-Suppress") != null) {
            return Optional.of(MailClassification.autoReply());
        }
        String[] precedence = message.getHeader("Precedence");
        if (precedence != null && precedence.length > 0) {
            String value = precedence[0].trim().toLowerCase(Locale.ROOT);
            if (value.equals("auto_reply") || value.equals("auto-reply")) {
                return Optional.of(MailClassification.autoReply());
            }
        }
        return Optional.empty();
    }
}
