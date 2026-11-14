package io.softa.starter.message.mail.classifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import io.softa.starter.message.mail.support.BounceInfo;
import io.softa.starter.message.mail.support.MailClassification;

/**
 * Stateless helpers shared by {@link MailClassificationRule}s.
 * <p>
 * Kept as a utility class (not a Spring bean) because every method is pure —
 * making them components would pull lifecycle ceremony for no gain, and
 * unit tests for individual rules can call these directly.
 */
@Slf4j
@UtilityClass
public class MailClassificationSupport {

    private static final String MESSAGE_ID = "Message-ID";

    /**
     * Pull the original {@code Message-ID} that this mail refers to — the mail a
     * bounce or a read receipt reports on — in the form a send record stores it:
     * JavaMail's own {@code <id@host>}, angle brackets kept and case untouched, so
     * {@code findByMessageIds} matches it exactly. Looked up in order, the first
     * found wins:
     * <ol>
     *   <li>this mail's own {@code In-Reply-To}, then the first entry of
     *       {@code References};</li>
     *   <li>a read receipt's {@code message/disposition-notification} part: its
     *       {@code Original-Message-ID} field (RFC 8098);</li>
     *   <li>the returned original's headers — a {@code text/rfc822-headers} or
     *       {@code message/rfc822} part, a DSN's third part (RFC 3464, RFC 6522) or
     *       the original a non-standard bounce attaches: its {@code Message-ID}.</li>
     * </ol>
     * RFC 3464 does not ask a DSN to carry {@code In-Reply-To} or {@code References},
     * so a standard bounce is usually linked by step 3. Steps 2 and 3 read the direct
     * parts of a multipart mail and are best-effort: a part that cannot be read is
     * skipped, never failing the rule that asked. Returns {@code null} when nothing
     * is found — the caller treats that as "no correlation possible".
     */
    public static String extractOriginalMessageId(MimeMessage msg) throws MessagingException {
        String id = firstMessageId(msg.getHeader("In-Reply-To"));
        if (id == null) {
            // References carries a space-separated list oldest-first; the first
            // entry is the thread root.
            id = firstMessageId(msg.getHeader("References"));
        }
        return id != null ? id : fromReportParts(msg);
    }

    /**
     * Steps 2 and 3 of {@link #extractOriginalMessageId}: an {@code Original-Message-ID}
     * wins over the returned headers, wherever the two parts sit.
     */
    private static String fromReportParts(MimeMessage msg) {
        String returnedOriginal = null;
        try {
            if (!(msg.getContent() instanceof Multipart multipart)) {
                return null;
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                try {
                    if (part.isMimeType("message/disposition-notification")) {
                        String id = normalizeMessageId(headersOf(part).getHeader("Original-Message-ID", null));
                        if (id != null) {
                            return id;
                        }
                    } else if (returnedOriginal == null && part.isMimeType("text/rfc822-headers")) {
                        returnedOriginal = normalizeMessageId(headersOf(part).getHeader(MESSAGE_ID, null));
                    } else if (returnedOriginal == null && part.isMimeType("message/rfc822")) {
                        // The stock handler parses it as a message (on IMAP, from the
                        // server's structure) — cheaper than re-reading its raw stream.
                        returnedOriginal = part.getContent() instanceof MimePart original
                                ? normalizeMessageId(original.getHeader(MESSAGE_ID, null)) : null;
                    }
                } catch (MessagingException | IOException e) {
                    log.debug("Original Message-ID: skipping unreadable part {} of {}: {}",
                            i, safeMessageId(msg), e.getMessage());
                }
            }
        } catch (MessagingException | IOException e) {
            log.debug("Original Message-ID: unreadable body of {}: {}", safeMessageId(msg), e.getMessage());
        }
        return returnedOriginal;
    }

    /** The fields of a part whose body is a header block: RFC 822 headers or report fields. */
    private static InternetHeaders headersOf(Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            return new InternetHeaders(in);
        }
    }

    private static String firstMessageId(String[] headerValues) {
        return headerValues == null || headerValues.length == 0 ? null : normalizeMessageId(headerValues[0]);
    }

    /**
     * The first msg-id in a header value, as a send record stores one:
     * {@code <left@right>} with its angle brackets, case untouched (the stored value
     * is JavaMail's verbatim, and PostgreSQL compares case-sensitively). A bare
     * {@code left@right} — some gateways drop the brackets — is wrapped; anything
     * else yields {@code null}.
     */
    static String normalizeMessageId(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String value = MimeUtility.unfold(headerValue).trim();
        int open = value.indexOf('<');
        int close = open < 0 ? -1 : value.indexOf('>', open);
        if (close > open + 1) {
            return value.substring(open, close + 1);
        }
        if (open < 0 && value.indexOf('@') > 0 && value.chars().noneMatch(Character::isWhitespace)) {
            return "<" + value + ">";
        }
        return null;
    }

    /**
     * The text of a part. A report part whose type has no content handler in Jakarta
     * Mail — {@code message/delivery-status}, {@code text/rfc822-headers} — gives a
     * raw stream from {@code getContent()}, never a String, so it is read from
     * {@link Part#getInputStream()} instead (US-ASCII by RFC 3464; UTF-8 also covers
     * RFC 6533).
     */
    public static String partText(Part part) throws MessagingException, IOException {
        if (part.getContent() instanceof String text) {
            return text;
        }
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
