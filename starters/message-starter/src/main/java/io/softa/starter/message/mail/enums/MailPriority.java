package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Email priority levels.
 * <p>
 * Maps to three standard headers for maximum client compatibility:
 * <ul>
 *   <li>{@code X-Priority} — non-standard but most widely supported (Outlook, Thunderbird, webmail)</li>
 *   <li>{@code Importance} — RFC 2156 standard (Exchange/Outlook, enterprise gateways)</li>
 *   <li>{@code X-MSMail-Priority} — Microsoft proprietary (all Outlook versions)</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum MailPriority {

    HIGH("High", "1", "High", "High"),
    NORMAL("Normal", "3", "Normal", "Normal"),
    LOW("Low", "5", "Low", "Low");

    @JsonValue
    private final String code;

    /** Value for the {@code X-Priority} header (1=Highest, 3=Normal, 5=Lowest). */
    private final String xPriority;

    /** Value for the {@code Importance} header (RFC 2156: High/Normal/Low). */
    private final String importance;

    /** Value for the {@code X-MSMail-Priority} header (High/Normal/Low). */
    private final String xMsMailPriority;
}
