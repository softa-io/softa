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
        assertEquals(ErrorCategory.INVALID_INPUT, classifier.classify(ErrorClassifier.CONTENT_INVALID, null));
    }

    @Test
    void configProblems_areNotRetryable() {
        // Fails the record on first sight; a missing server or template must not burn the retry budget.
        assertEquals(false, classifier.classify(ErrorClassifier.CONFIG_NOT_RESOLVABLE, null).isRetryable());
        // Checked before the heuristics: "QUOTA" in the code must not make a used-up month retryable.
        assertEquals(false, classifier.classify(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, null).isRetryable());
    }

    @Test
    void unmatchedCodeAndMessage_fallBackToUnknown() {
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify("X999", "something odd"));
        assertEquals(ErrorCategory.UNKNOWN, classifier.classify(null, null));
    }
}
