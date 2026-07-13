package io.softa.starter.message.mail.classifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.mail.internet.MimeMessage;
import lombok.experimental.UtilityClass;

import io.softa.starter.message.mail.enums.EnhancedStatusCode;
import io.softa.starter.message.mail.enums.SmtpReplyCode;
import io.softa.starter.message.mail.support.BounceInfo;

/**
 * Best-effort enrichment of a {@link BounceInfo} from free-form body text.
 * Used by {@link MailerDaemonRule} and {@link KeywordRule} when the message
 * has no structured DSN attached.
 * <p>
 * Stateless — every method is pure, so no Spring bean.
 */
@UtilityClass
public class BounceBodyExtractor {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** Diagnostic-message cap — avoids shipping a 5 MB bounce verbatim to the DB. */
    private static final int DIAGNOSTIC_MAX_CHARS = 500;

    public static void enrich(BounceInfo info, MimeMessage msg) throws Exception {
        String body = MailClassificationSupport.extractPlainText(msg);
        String subject = msg.getSubject();
        String combined = (subject != null ? subject : "") + " " + body;

        SmtpReplyCode.fromContent(combined).ifPresent(c -> {
            info.setSmtpReplyCode(c.getCode());
            info.setPermanent(c.isPermanent());
        });

        EnhancedStatusCode.extractRaw(combined).ifPresent(info::setEnhancedStatusCode);

        if (body != null && body.length() > DIAGNOSTIC_MAX_CHARS) {
            info.setDiagnosticMessage(body.substring(0, DIAGNOSTIC_MAX_CHARS));
        } else {
            info.setDiagnosticMessage(body);
        }

        info.setFailedRecipients(extractRecipients(body));
    }

    private static List<String> extractRecipients(String body) {
        List<String> recipients = new ArrayList<>();
        if (body == null) return recipients;
        Matcher m = EMAIL_PATTERN.matcher(body);
        while (m.find()) {
            String email = m.group();
            String lower = email.toLowerCase();
            if (lower.startsWith("mailer-daemon") || lower.startsWith("postmaster")) continue;
            recipients.add(email);
        }
        return recipients;
    }
}
