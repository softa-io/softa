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
    void unmatchedCodeAndMessage_fallBackToUnknown() {
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("X999", "something odd"));
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify(null, null));
    }
}
