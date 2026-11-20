package io.softa.starter.message.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void preparationMarkerCodes_mapToConfig() {
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.CONFIG_NOT_RESOLVABLE, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.TEMPLATE_NOT_RESOLVABLE, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.DAILY_QUOTA_EXCEEDED, null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify(ErrorClassifier.CONTENT_INVALID, null));
    }

    @Test
    void configProblems_areNotRetryable() {
        // Fails the record on first sight; a missing server or template must not burn the retry budget.
        assertEquals(false, classifier.classify(ErrorClassifier.CONFIG_NOT_RESOLVABLE, null).isRetryable());
        // Checked before the heuristics: "QUOTA" in the code must not make a used-up month retryable.
        assertEquals(false, classifier.classify(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, null).isRetryable());
        // Nor a used-up day: it fails the record like the month, to be retried once the day turns.
        assertEquals(false, classifier.classify(ErrorClassifier.DAILY_QUOTA_EXCEEDED, null).isRetryable());
    }

    @Test
    void smtpReplyCodes_followTheirClass() {
        // SmtpMailTransport codes a refusal with the server's reply: 4xx is temporary, 5xx is not.
        assertEquals(ErrorCategory.PERMANENT, classifier.classify("SMTP_550", null));
        assertEquals(ErrorCategory.PERMANENT, classifier.classify("SMTP_554", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("SMTP_421", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("SMTP_451", null));
        assertEquals(ErrorCategory.AUTH, classifier.classify("SMTP_535", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("SMTP_552", null));
    }

    @Test
    void wrappedSmtpExceptionTypes_mapToWhatTheyMean() {
        assertEquals(ErrorCategory.AUTH, classifier.classify("SMTP_MailAuthenticationException", null));
        assertEquals(ErrorCategory.AUTH, classifier.classify("SMTP_AuthenticationFailedException", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("SMTP_AddressException", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("SMTP_MailSendException", null));
    }

    @Test
    void httpStatusCodes_mapByStatus() {
        // An SMS adapter codes an HTTP error it could not read a provider code from as HTTP_<status>.
        assertEquals(ErrorCategory.AUTH, classifier.classify("HTTP_401", null));
        assertEquals(ErrorCategory.AUTH, classifier.classify("HTTP_403", null));
        assertEquals(ErrorCategory.QUOTA, classifier.classify("HTTP_429", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("HTTP_400", "Request cannot be processed."));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("HTTP_422", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("HTTP_408", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("HTTP_503", null));
        // A status with no rule of its own falls through to the message.
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("HTTP_409", "conflict"));
    }

    @Test
    void infobipStatusNames_mapToWhatFixesThem() {
        assertEquals(ErrorCategory.CONFIG, classifier.classify("REJECTED_SOURCE", "Sender ID not registered"));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("REJECTED_NOT_ENOUGH_CREDITS", null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("REJECTED_ROUTE_NOT_AVAILABLE", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("REJECTED_PREFIX_MISSING", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("MISSING_TO", null));
        assertEquals(ErrorCategory.PERMANENT, classifier.classify("REJECTED_DND", null));
        assertEquals(ErrorCategory.PERMANENT, classifier.classify("REJECTED_FLOODING_FILTER", null));
        // Only Infobip's own platform failures are worth another attempt.
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("REJECTED_SYSTEM_ERROR", null));
        assertEquals(false, classifier.classify("REJECTED_SOURCE", null).isRetryable());
    }

    @Test
    void unmatchedCodeAndMessage_fallBackToUnknown() {
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("X999", "something odd"));
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify(null, null));
    }
}
