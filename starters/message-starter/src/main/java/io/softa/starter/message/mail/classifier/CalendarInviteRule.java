package io.softa.starter.message.mail.classifier;

import java.util.Locale;
import java.util.Optional;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Detects iCalendar payloads — meeting invitations, cancellations, and replies
 * (RFC 5546 / 5545). Matches when:
 * <ul>
 *   <li>Top-level {@code Content-Type} is {@code text/calendar}, or</li>
 *   <li>Any direct sub-part of a multipart message is {@code text/calendar}.</li>
 * </ul>
 * Runs after read-receipt detection (which has its own dedicated multipart/report
 * shape) and before the bounce / auto-reply / mailing-list detectors so that a
 * calendar invite doesn't get reclassified as bulk because of a {@code Precedence}
 * header set by the calendar server.
 */
@Component
@Order(15)
public class CalendarInviteRule implements MailClassificationRule {

    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        if (message.isMimeType("text/calendar") || message.isMimeType("application/ics")) {
            return Optional.of(MailClassification.calendarInvite());
        }
        if (message.isMimeType("multipart/*")) {
            Object content;
            try {
                content = message.getContent();
            } catch (Exception e) {
                return Optional.empty();
            }
            if (content instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    Part bp = mp.getBodyPart(i);
                    if (bp.isMimeType("text/calendar") || bp.isMimeType("application/ics")) {
                        return Optional.of(MailClassification.calendarInvite());
                    }
                }
            }
        }
        // Defensive: some Outlook variants put the calendar method directly in the
        // top-level Content-Type as a parameter (e.g. text/calendar; method=REQUEST).
        // The isMimeType check above already covers this, but if the message
        // doesn't expose isMimeType cleanly, fall back to a raw header check.
        String ct = message.getContentType();
        if (ct != null && ct.toLowerCase(Locale.ROOT).contains("text/calendar")) {
            return Optional.of(MailClassification.calendarInvite());
        }
        return Optional.empty();
    }
}
