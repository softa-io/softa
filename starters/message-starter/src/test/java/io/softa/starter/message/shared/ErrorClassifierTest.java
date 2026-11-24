package io.softa.starter.message.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void aliyunCodes_followTheTable() {
        assertEquals(ErrorCategory.AUTH, classifier.classify("SignatureDoesNotMatch", null));
        assertEquals(ErrorCategory.AUTH, classifier.classify("isp.RAM_PERMISSION_DENY", null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("Amount.NotEnough", null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("isv.DOMESTIC_NUMBER_NOT_SUPPORTED", null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("isv.SMS_TEMPLATE_ILLEGAL", null));
        assertEquals(ErrorCategory.QUOTA, classifier.classify("isv.BUSINESS_LIMIT_CONTROL", null));
        assertEquals(ErrorCategory.QUOTA, classifier.classify("Throttling.User", null));
        // The account's daily limit lifts tomorrow: not retried.
        assertEquals(ErrorCategory.CONFIG, classifier.classify("isv.DAY_LIMIT_CONTROL", null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("DayLimitControl", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("isv.MOBILE_NUMBER_ILLEGAL", null));
        assertEquals(ErrorCategory.PERMANENT, classifier.classify("isv.CUSTOMER_REFUSED", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("isp.SYSTEM_ERROR", null));
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify("SignatureNonceUsed", null));
    }

    @Test
    void aliyunCodes_theKeywordsMisfiled() {
        // "LIMIT" read as a rate limit: a blocked keyword and an over-long variable are the content's fault.
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("isv.BLACK_KEY_CONTROL_LIMIT", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("isv.PARAM_LENGTH_LIMIT", null));
        // "Invalid" read as bad input: an unknown AccessKey is the credentials.
        assertEquals(ErrorCategory.AUTH, classifier.classify("InvalidAccessKeyId.NotFound", null));
        // An account out of balance is not retried: it waits for a top-up, then a manual retry.
        assertEquals(false, classifier.classify("isv.AMOUNT_NOT_ENOUGH", null).isRetryable());
    }

    @Test
    void preparationMarkerCodes_mapToConfig() {
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.CONFIG_NOT_RESOLVABLE, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.TEMPLATE_NOT_RESOLVABLE, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.DAILY_QUOTA_EXCEEDED, null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify(ErrorClassifier.CONTENT_INVALID, null));
    }

    @Test
    void eachLimitType_isHandledByWhenItLifts() {
        // Quotas the platform sets per tenant, and the server's or provider's own daily limit: tomorrow.
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.DAILY_QUOTA_EXCEEDED, null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, null));
        assertEquals(ErrorCategory.CONFIG,
                classifier.classify(ErrorClassifier.SEND_LIMIT_DAILY_EXCEEDED, "Rate limit reached"));
        // The per-minute window is deferred, not failed; should it be classified, it is a rate limit.
        assertEquals(ErrorCategory.QUOTA, classifier.classify(ErrorClassifier.SEND_LIMIT_MINUTE_EXCEEDED, null));
        // The provider's own throttling.
        assertEquals(ErrorCategory.QUOTA, classifier.classify("HTTP_429", null));
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
        // The provider failing while it handled the request may come after it sent the text.
        assertEquals(ErrorCategory.OUTCOME_UNKNOWN, classifier.classify("HTTP_500", null));
        assertEquals(ErrorCategory.OUTCOME_UNKNOWN, classifier.classify("HTTP_502", null));
        assertEquals(ErrorCategory.OUTCOME_UNKNOWN, classifier.classify("HTTP_504", null));
        // A status with no rule of its own falls through to the message.
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("HTTP_409", "conflict"));
    }

    @Test
    void unansweredCalls_retryOnlyWhatNeverReachedTheProvider() {
        assertEquals(ErrorCategory.TRANSIENT,
                classifier.classify(ErrorClassifier.CONNECT_FAILED, "CM not reached: Connection refused"));
        // The marker wins over the message heuristics, which would read "timed out" as TRANSIENT.
        assertEquals(ErrorCategory.OUTCOME_UNKNOWN,
                classifier.classify(ErrorClassifier.OUTCOME_UNKNOWN, "I/O error on POST: Read timed out"));
        assertFalse(ErrorCategory.OUTCOME_UNKNOWN.isRetryable());
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
    void oneWaySmsResults_followTheTable() {
        assertEquals(ErrorCategory.AUTH, classifier.classify("ONEWAYSMS_-100", null));
        assertEquals(ErrorCategory.CONFIG, classifier.classify("ONEWAYSMS_-200", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("ONEWAYSMS_-300", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("ONEWAYSMS_-400", null));
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify("ONEWAYSMS_-500", null));
        // Out of credit: waits for a top-up, then a manual retry.
        assertEquals(ErrorCategory.CONFIG, classifier.classify("ONEWAYSMS_-600", null));
        // A result the API does not document is retried as unknown.
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("ONEWAYSMS_-700", null));
    }

    @Test
    void unmatchedCodeAndMessage_fallBackToUnknown() {
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("X999", "something odd"));
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify(null, null));
    }
}
