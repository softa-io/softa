package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Classification of a received email after analysis by
 * {@link io.softa.starter.message.mail.support.MailClassifier}.
 */
@Getter
@AllArgsConstructor
public enum ReceivedMailType {

    NORMAL("Normal", "Regular email"),
    READ_RECEIPT("ReadReceipt", "MDN read receipt (RFC 8098)"),
    BOUNCE("Bounce", "Delivery status notification / bounce / rejection (RFC 3464)");

    @JsonValue
    private final String code;
    private final String description;
}
