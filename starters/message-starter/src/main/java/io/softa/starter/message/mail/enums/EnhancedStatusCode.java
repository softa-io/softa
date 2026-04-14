package io.softa.starter.message.mail.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced Mail Status Codes (RFC 3463).
 * <p>
 * Format: {@code X.Y.Z} where X is the class (2=Success, 4=Transient, 5=Permanent),
 * Y is the subject, and Z is the detail.
 */
@Getter
@AllArgsConstructor
public enum EnhancedStatusCode {

    // --- Address-related ---
    X5_1_1("5.1.1", "Bad destination mailbox address"),
    X5_1_2("5.1.2", "Bad destination system address"),
    X5_1_3("5.1.3", "Bad destination mailbox address syntax"),

    // --- Mailbox-related ---
    X4_2_2("4.2.2", "Mailbox full (temporary)"),
    X5_2_2("5.2.2", "Mailbox full (permanent)"),
    X5_2_3("5.2.3", "Message length exceeds administrative limit"),

    // --- Mail system ---
    X5_3_4("5.3.4", "Message too big for system"),
    X4_3_1("4.3.1", "Mail system full"),
    X4_3_2("4.3.2", "System not accepting network messages"),

    // --- Security/Policy ---
    X5_7_1("5.7.1", "Delivery not authorized, message refused");

    private final String code;
    private final String description;

    /** Regex to extract X.Y.Z enhanced status codes from diagnostic text. */
    private static final Pattern PATTERN = Pattern.compile("([245])\\.([0-7])\\.([0-9]{1,3})");

    /** Whether this is a permanent failure (5.x.x). */
    public boolean isPermanent() {
        return code.startsWith("5");
    }

    /** Whether this is a transient failure (4.x.x). */
    public boolean isTransient() {
        return code.startsWith("4");
    }

    /**
     * Try to extract an enhanced status code from the given text content.
     *
     * @param content email body or diagnostic text to scan
     * @return the first matching known code, or empty if none found
     */
    public static Optional<EnhancedStatusCode> fromContent(String content) {
        if (content == null) return Optional.empty();
        Matcher m = PATTERN.matcher(content);
        if (m.find()) {
            String found = m.group();
            return Arrays.stream(values())
                    .filter(c -> c.code.equals(found))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * Extract the raw X.Y.Z code string from content, even if it is not a known enum value.
     *
     * @param content diagnostic text
     * @return the raw code string, or empty if no pattern match
     */
    public static Optional<String> extractRaw(String content) {
        if (content == null) return Optional.empty();
        Matcher m = PATTERN.matcher(content);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }
}
