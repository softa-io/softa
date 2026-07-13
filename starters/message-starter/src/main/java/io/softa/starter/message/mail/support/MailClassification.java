package io.softa.starter.message.mail.support;

import lombok.Data;

import io.softa.starter.message.mail.enums.ReceivedMailType;

/**
 * Result of classifying a received email via {@link MailClassifier}.
 * <p>
 * Carries one mutually-exclusive primary {@link #type} plus a set of
 * orthogonal boolean flags (mailing-list, encrypted, spam) that may apply
 * on top of any primary type — e.g. a {@code BOUNCE} that was distributed
 * via a mailing list, or a {@code CALENDAR_INVITE} that the spam filter
 * flagged. Modeling them as independent fields preserves both signals so
 * downstream consumers can act on either or both.
 */
@Data
public class MailClassification {

    /** Mutually-exclusive content type — what this email is. */
    private ReceivedMailType type;

    /**
     * True when {@code List-Id} / {@code List-Unsubscribe} or
     * {@code Precedence: list|bulk} indicates the message was distributed
     * via a mailing list. Orthogonal to {@link #type}.
     */
    private boolean mailingList;

    /**
     * True when the message body is encrypted via PGP-MIME
     * ({@code multipart/encrypted}, RFC 3156) or S/MIME
     * ({@code application/pkcs7-mime}, RFC 8551).
     */
    private boolean encrypted;

    /**
     * True when standard anti-spam markers are present
     * ({@code X-Spam-Flag: YES}, {@code X-Spam-Status: Yes}, etc.).
     * Reputation overlay; orthogonal to {@link #type}.
     */
    private boolean spam;

    /** Original Message-ID that this email refers to (for receipt/bounce linking). */
    private String originalMessageId;

    /** Structured bounce data. Only populated when {@link #type} is {@code BOUNCE}. */
    private BounceInfo bounceInfo;

    public static MailClassification normal() {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.NORMAL);
        return c;
    }

    public static MailClassification unknown() {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.UNKNOWN);
        return c;
    }

    public static MailClassification readReceipt(String originalMessageId) {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.READ_RECEIPT);
        c.setOriginalMessageId(originalMessageId);
        return c;
    }

    public static MailClassification bounce(BounceInfo bounceInfo) {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.BOUNCE);
        if (bounceInfo != null) {
            c.setOriginalMessageId(bounceInfo.getOriginalMessageId());
            c.setBounceInfo(bounceInfo);
        }
        return c;
    }

    public static MailClassification autoReply() {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.AUTO_REPLY);
        return c;
    }

    public static MailClassification calendarInvite() {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.CALENDAR_INVITE);
        return c;
    }
}
