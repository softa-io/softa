package io.softa.starter.message.mail.classifier;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.experimental.UtilityClass;

import io.softa.starter.message.mail.support.BounceInfo;
import io.softa.starter.message.mail.support.MailClassification;

/**
 * Stateless helpers shared by {@link MailClassificationRule}s.
 * <p>
 * Kept as a utility class (not a Spring bean) because every method is pure —
 * making them components would pull lifecycle ceremony for no gain, and
 * unit tests for individual rules can call these directly.
 */
@UtilityClass
public class MailClassificationSupport {

    /**
     * Pull the original {@code Message-ID} that this mail refers to, inspecting
     * {@code In-Reply-To} (preferred) then {@code References}. Returns {@code null}
     * if neither header is present — the caller treats that as "no correlation possible".
     */
    public static String extractOriginalMessageId(MimeMessage msg) throws MessagingException {
        String[] inReplyTo = msg.getHeader("In-Reply-To");
        if (inReplyTo != null && inReplyTo.length > 0) {
            return inReplyTo[0].trim();
        }
        String[] refs = msg.getHeader("References");
        if (refs != null && refs.length > 0) {
            // References header carries a space-separated list oldest-first;
            // the first entry is the thread root.
            return refs[0].trim().split("\\s+")[0];
        }
        return null;
    }

    /**
     * Concatenated plain-text body of a MIME message. Traverses nested
     * multipart structures; non-text parts are ignored.
     */
    public static String extractPlainText(MimeMessage msg) throws Exception {
        Object content = msg.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof MimeMultipart multipart) {
            return extractTextFromMultipart(multipart);
        }
        return "";
    }

    private static String extractTextFromMultipart(MimeMultipart multipart) throws Exception {
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

    /**
     * Build a heuristic {@code BOUNCE} classification: correlate the original {@code Message-ID} and
     * enrich the {@link BounceInfo} from body heuristics. Shared by the sender-based
     * ({@link MailerDaemonRule}) and keyword-based ({@link KeywordRule}) bounce rules, which differ only
     * in their match predicate.
     */
    public static MailClassification heuristicBounce(MimeMessage message) throws Exception {
        BounceInfo info = new BounceInfo();
        info.setOriginalMessageId(extractOriginalMessageId(message));
        BounceBodyExtractor.enrich(info, message);
        return MailClassification.bounce(info);
    }

    /** Safe {@code Message-ID} accessor for log diagnostics. */
    public static String safeMessageId(MimeMessage msg) {
        try {
            return msg.getMessageID();
        } catch (MessagingException e) {
            return "unknown";
        }
    }
}
