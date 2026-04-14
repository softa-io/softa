package io.softa.starter.message.mail.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * Standard SMTP reply codes relevant to bounce/rejection detection (RFC 5321).
 * <p>
 * 4xx codes indicate transient failures (retryable); 5xx codes indicate permanent failures.
 */
@Getter
@AllArgsConstructor
public enum SmtpReplyCode {

    CODE_421("421", "Service not available, closing transmission channel"),
    CODE_450("450", "Requested mail action not taken: mailbox unavailable (busy)"),
    CODE_451("451", "Requested action aborted: local error in processing"),
    CODE_452("452", "Requested action not taken: insufficient system storage"),
    CODE_550("550", "Requested action not taken: mailbox unavailable"),
    CODE_551("551", "User not local; please try forwarding"),
    CODE_552("552", "Requested mail action aborted: exceeded storage allocation"),
    CODE_553("553", "Requested action not taken: mailbox name not allowed");

    private final String code;
    private final String description;

    /** Whether this is a permanent failure (5xx). */
    public boolean isPermanent() {
        return code.startsWith("5");
    }

    /** Whether this is a transient failure (4xx). */
    public boolean isTransient() {
        return code.startsWith("4");
    }

    /**
     * Try to find a matching SMTP reply code in the given text content.
     *
     * @param content email body or diagnostic text to scan
     * @return the first matching code, or empty if none found
     */
    public static Optional<SmtpReplyCode> fromContent(String content) {
        if (content == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(c -> content.contains(c.code))
                .findFirst();
    }
}
