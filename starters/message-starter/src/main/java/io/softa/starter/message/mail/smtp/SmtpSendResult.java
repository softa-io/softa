package io.softa.starter.message.mail.smtp;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Outcome of a single SMTP send attempt.
 */
@Data
@AllArgsConstructor
public class SmtpSendResult {

    private boolean success;
    private String messageId;
    private String errorCode;
    private String errorMessage;

    public static SmtpSendResult success(String messageId) {
        return new SmtpSendResult(true, messageId, null, null);
    }

    public static SmtpSendResult failure(String errorCode, String errorMessage) {
        return new SmtpSendResult(false, null, errorCode, errorMessage);
    }
}
