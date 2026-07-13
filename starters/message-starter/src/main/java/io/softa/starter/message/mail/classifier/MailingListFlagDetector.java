package io.softa.starter.message.mail.classifier;

import java.util.Locale;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Sets {@code mailingList = true} when the message bears markers of having
 * been distributed via a mailing list. Match criteria, in decreasing
 * strength of signal:
 * <ul>
 *   <li>{@code List-Id} header (RFC 2919) — the strongest, deliberately
 *       set by the list manager.</li>
 *   <li>{@code List-Unsubscribe} header (RFC 2369) — present on most modern
 *       list mail to satisfy CAN-SPAM / GDPR unsubscribe requirements.</li>
 *   <li>{@code Precedence: list} or {@code Precedence: bulk} — a legacy
 *       convention still seen on some commercial bulk senders.</li>
 * </ul>
 * <p>
 * Mailing-list distribution is orthogonal to content type — a list-delivered
 * message can also be a bounce, an auto-reply, a calendar invite, etc. The
 * flag preserves the transport signal without overriding the primary type.
 */
@Component
public class MailingListFlagDetector implements MailFlagDetector {

    @Override
    public void apply(MimeMessage message, MailClassification classification) throws Exception {
        if (hasNonEmptyHeader(message, "List-Id")
                || hasNonEmptyHeader(message, "List-Unsubscribe")) {
            classification.setMailingList(true);
            return;
        }
        String[] precedence = message.getHeader("Precedence");
        if (precedence != null && precedence.length > 0) {
            String value = precedence[0].trim().toLowerCase(Locale.ROOT);
            if (value.equals("list") || value.equals("bulk")) {
                classification.setMailingList(true);
            }
        }
    }

    private static boolean hasNonEmptyHeader(MimeMessage message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values != null && values.length > 0
                && values[0] != null && !values[0].trim().isEmpty();
    }
}
