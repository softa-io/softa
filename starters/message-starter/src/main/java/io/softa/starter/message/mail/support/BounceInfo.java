package io.softa.starter.message.mail.support;

import lombok.Data;

import java.util.List;

/**
 * Structured bounce/rejection data extracted from a DSN or bounce email.
 * <p>
 * Fields are stored as raw strings to accommodate unknown/non-standard codes
 * that may not be represented by the {@code SmtpReplyCode} or {@code EnhancedStatusCode} enums.
 */
@Data
public class BounceInfo {

    /** Original Message-ID of the email that bounced (from In-Reply-To or References header). */
    private String originalMessageId;

    /** 3-digit SMTP reply code, e.g. "550". May be null if not parseable. */
    private String smtpReplyCode;

    /** Enhanced status code (RFC 3463), e.g. "5.1.1". May be null if not parseable. */
    private String enhancedStatusCode;

    /** Full diagnostic message, e.g. "smtp; 550 5.1.1 User unknown". */
    private String diagnosticMessage;

    /** DSN action field: "failed", "delayed", "delivered", "relayed", "expanded". */
    private String action;

    /** List of recipient addresses that failed delivery. */
    private List<String> failedRecipients;

    /** {@code true} for permanent failures (5xx), {@code false} for transient (4xx). */
    private boolean permanent;
}
