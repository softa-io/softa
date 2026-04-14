package io.softa.starter.message.mail.support;

import io.softa.starter.message.mail.enums.ReceivedMailType;
import lombok.Data;

/**
 * Result of classifying a received email via {@link MailClassifier}.
 */
@Data
public class MailClassification {

    /** The determined type: NORMAL, READ_RECEIPT, or BOUNCE. */
    private ReceivedMailType type;

    /** Original Message-ID that this email refers to (for receipt/bounce linking). */
    private String originalMessageId;

    /** Structured bounce data. Only populated when {@link #type} is {@code BOUNCE}. */
    private BounceInfo bounceInfo;

    public static MailClassification normal() {
        MailClassification c = new MailClassification();
        c.setType(ReceivedMailType.NORMAL);
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
}
