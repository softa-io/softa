package io.softa.starter.message.mail.classifier;

import java.util.Locale;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Sets {@code spam = true} when standard anti-spam scanners have annotated
 * the message. There is no single RFC, but a handful of headers are
 * de-facto universal:
 * <ul>
 *   <li>{@code X-Spam-Flag: YES} — set by SpamAssassin / Postfix /
 *       most open-source filters.</li>
 *   <li>{@code X-Spam-Status: Yes, ...} — older variant, same semantics.</li>
 *   <li>{@code X-MS-Exchange-Organization-SCL} ≥ 5 — Exchange's spam
 *       confidence level (5–9 = spam).</li>
 * </ul>
 * <p>
 * Spam reputation is orthogonal to content type — backscatter spam looks
 * like a bounce, phishing can disguise as a calendar invite, etc. The flag
 * lets downstream UI quarantine without losing the underlying classification.
 * <p>
 * Authentication-Results parsing (SPF / DKIM / DMARC pass/fail) is
 * deliberately not handled here — it's a richer signal that deserves its own
 * detector if and when needed.
 */
@Component
public class SpamFlagDetector implements MailFlagDetector {

    @Override
    public void apply(MimeMessage message, MailClassification classification) throws Exception {
        if (hasFlagYes(message, "X-Spam-Flag")) {
            classification.setSpam(true);
            return;
        }
        if (hasStatusYes(message, "X-Spam-Status")) {
            classification.setSpam(true);
            return;
        }
        if (exceedsSclThreshold(message)) {
            classification.setSpam(true);
        }
    }

    private static boolean hasFlagYes(MimeMessage m, String header) throws Exception {
        String[] values = m.getHeader(header);
        if (values == null || values.length == 0 || values[0] == null) return false;
        return "yes".equals(values[0].trim().toLowerCase(Locale.ROOT));
    }

    /** {@code X-Spam-Status: Yes, score=...} — only the leading verdict matters. */
    private static boolean hasStatusYes(MimeMessage m, String header) throws Exception {
        String[] values = m.getHeader(header);
        if (values == null || values.length == 0 || values[0] == null) return false;
        String v = values[0].trim().toLowerCase(Locale.ROOT);
        return v.startsWith("yes");
    }

    /** Exchange SCL: 0–4 = ham, 5+ = spam (5 = junk, 6+ = high-confidence spam). */
    private static boolean exceedsSclThreshold(MimeMessage m) throws Exception {
        String[] values = m.getHeader("X-MS-Exchange-Organization-SCL");
        if (values == null || values.length == 0 || values[0] == null) return false;
        try {
            return Integer.parseInt(values[0].trim()) >= 5;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
