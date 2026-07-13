package io.softa.starter.message.shared;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Maps provider error codes / messages onto an {@link ErrorCategory}.
 * <p>
 * Implementation is intentionally defensive: when in doubt, return
 * {@link ErrorCategory#UNKNOWN} so retry keeps running. Specific provider
 * code tables can be folded in over time (SendGrid / Twilio / Aliyun) without
 * changing callers.
 */
@Component
public class ErrorClassifier {

    public ErrorCategory classify(String errorCode, String errorMessage) {
        if (errorCode != null) {
            ErrorCategory c = classifyByCode(errorCode.trim());
            if (c != null) return c;
        }
        if (errorMessage != null) {
            ErrorCategory c = classifyByMessage(errorMessage.toLowerCase(Locale.ROOT));
            if (c != null) return c;
        }
        return ErrorCategory.UNKNOWN;
    }

    private ErrorCategory classifyByCode(String code) {
        // Our own marker codes emitted from the service layer.
        if (code.equals("PROVIDER_NOT_FOUND")
                || code.equals("PROVIDER_RESOLVE_FAILED")) {
            return ErrorCategory.AUTH;
        }
        // SMTP reply codes from SmtpMailTransport: "SMTP_<ExceptionType>".
        if (code.startsWith("SMTP_")) {
            return classifySmtpExceptionType(code.substring(5));
        }
        // 5xx SMTP reply or Twilio permanent.
        if (code.startsWith("550") || code.startsWith("551") || code.startsWith("553")) {
            return ErrorCategory.PERMANENT;
        }
        if (code.startsWith("552") /* message too large */) {
            return ErrorCategory.INVALID_INPUT;
        }
        if (code.startsWith("421") /* server not available */
                || code.startsWith("450") || code.startsWith("451") || code.startsWith("452")) {
            return ErrorCategory.TRANSIENT;
        }
        if (code.startsWith("535") /* auth failed */
                || code.startsWith("530")) {
            return ErrorCategory.AUTH;
        }
        // Aliyun / Tencent bizCode heuristics — rate limits usually contain "LIMIT".
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.contains("LIMIT") || upper.contains("QUOTA") || upper.contains("THROTTL")) {
            return ErrorCategory.QUOTA;
        }
        if (upper.contains("AUTH") || upper.contains("SIGNATURE") || upper.contains("FORBIDDEN")) {
            return ErrorCategory.AUTH;
        }
        if (upper.contains("INVALID") || upper.contains("MALFORM") || upper.contains("PARAM")) {
            return ErrorCategory.INVALID_INPUT;
        }
        if (upper.contains("TIMEOUT") || upper.contains("UNAVAILABLE") || upper.contains("RETRY")) {
            return ErrorCategory.TRANSIENT;
        }
        return null;
    }

    private ErrorCategory classifySmtpExceptionType(String exceptionType) {
        return switch (exceptionType) {
            case "AuthenticationFailedException" -> ErrorCategory.AUTH;
            case "SendFailedException" -> ErrorCategory.PERMANENT;
            case "MessagingException", "MailSendException", "MailException" -> ErrorCategory.TRANSIENT;
            default -> ErrorCategory.UNKNOWN;
        };
    }

    private ErrorCategory classifyByMessage(String msg) {
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection reset")
                || msg.contains("unavailable") || msg.contains("try again")) {
            return ErrorCategory.TRANSIENT;
        }
        if (msg.contains("rate limit") || msg.contains("too many requests")
                || msg.contains("quota") || msg.contains("throttle")) {
            return ErrorCategory.QUOTA;
        }
        if (msg.contains("unauthorized") || msg.contains("forbidden")
                || msg.contains("invalid credentials") || msg.contains("authentication failed")) {
            return ErrorCategory.AUTH;
        }
        if (msg.contains("invalid address") || msg.contains("malformed")
                || msg.contains("bad recipient") || msg.contains("no recipient")) {
            return ErrorCategory.INVALID_INPUT;
        }
        if (msg.contains("no such user") || msg.contains("user unknown")
                || msg.contains("does not exist")) {
            return ErrorCategory.PERMANENT;
        }
        return null;
    }
}
