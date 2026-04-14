package io.softa.starter.message.mail.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class EnhancedStatusCodeTest {

    @Test
    void permanentFailureCodes() {
        Assertions.assertTrue(EnhancedStatusCode.X5_1_1.isPermanent());
        Assertions.assertTrue(EnhancedStatusCode.X5_1_2.isPermanent());
        Assertions.assertTrue(EnhancedStatusCode.X5_2_2.isPermanent());
        Assertions.assertTrue(EnhancedStatusCode.X5_7_1.isPermanent());
    }

    @Test
    void transientFailureCodes() {
        Assertions.assertTrue(EnhancedStatusCode.X4_2_2.isTransient());
        Assertions.assertTrue(EnhancedStatusCode.X4_3_1.isTransient());
        Assertions.assertTrue(EnhancedStatusCode.X4_3_2.isTransient());
    }

    @Test
    void permanentAndTransientAreMutuallyExclusive() {
        for (EnhancedStatusCode code : EnhancedStatusCode.values()) {
            Assertions.assertNotEquals(code.isPermanent(), code.isTransient(),
                    "Code " + code.getCode() + " should be either permanent or transient");
        }
    }

    @Test
    void fromContentFindsKnownCode() {
        Optional<EnhancedStatusCode> result = EnhancedStatusCode.fromContent(
                "smtp; 550 5.1.1 User unknown");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(EnhancedStatusCode.X5_1_1, result.get());
    }

    @Test
    void fromContentFindsMailboxFull() {
        Optional<EnhancedStatusCode> result = EnhancedStatusCode.fromContent(
                "Mailbox full 4.2.2 try again later");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(EnhancedStatusCode.X4_2_2, result.get());
    }

    @Test
    void fromContentReturnsEmptyForUnknownCode() {
        // 5.4.7 is a valid format but not in our enum
        Optional<EnhancedStatusCode> result = EnhancedStatusCode.fromContent(
                "Delivery expired 5.4.7 message delayed too long");
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void fromContentReturnsEmptyForNull() {
        Assertions.assertFalse(EnhancedStatusCode.fromContent(null).isPresent());
    }

    @Test
    void fromContentReturnsEmptyForNoMatch() {
        Assertions.assertFalse(EnhancedStatusCode.fromContent("No status code here").isPresent());
    }

    @Test
    void extractRawFindsKnownCode() {
        Optional<String> result = EnhancedStatusCode.extractRaw("smtp; 550 5.1.1 User unknown");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals("5.1.1", result.get());
    }

    @Test
    void extractRawFindsUnknownCode() {
        // 5.4.7 is not in enum but should be extractable
        Optional<String> result = EnhancedStatusCode.extractRaw("5.4.7 message delayed");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals("5.4.7", result.get());
    }

    @Test
    void extractRawReturnsEmptyForNull() {
        Assertions.assertFalse(EnhancedStatusCode.extractRaw(null).isPresent());
    }

    @Test
    void extractRawReturnsEmptyForNoPattern() {
        Assertions.assertFalse(EnhancedStatusCode.extractRaw("No code here").isPresent());
    }

    @Test
    void extractRawDoesNotMatchInvalidClass() {
        // Class must be 2, 4, or 5 — class 3 should not match
        Assertions.assertFalse(EnhancedStatusCode.extractRaw("3.1.1 invalid class").isPresent());
    }

    @Test
    void codeAndDescriptionPopulated() {
        Assertions.assertEquals("5.1.1", EnhancedStatusCode.X5_1_1.getCode());
        Assertions.assertEquals("Bad destination mailbox address", EnhancedStatusCode.X5_1_1.getDescription());
    }
}
