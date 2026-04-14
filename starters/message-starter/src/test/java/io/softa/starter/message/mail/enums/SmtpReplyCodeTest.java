package io.softa.starter.message.mail.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class SmtpReplyCodeTest {

    @Test
    void permanentFailureCodes() {
        Assertions.assertTrue(SmtpReplyCode.CODE_550.isPermanent());
        Assertions.assertTrue(SmtpReplyCode.CODE_551.isPermanent());
        Assertions.assertTrue(SmtpReplyCode.CODE_552.isPermanent());
        Assertions.assertTrue(SmtpReplyCode.CODE_553.isPermanent());
    }

    @Test
    void transientFailureCodes() {
        Assertions.assertTrue(SmtpReplyCode.CODE_421.isTransient());
        Assertions.assertTrue(SmtpReplyCode.CODE_450.isTransient());
        Assertions.assertTrue(SmtpReplyCode.CODE_451.isTransient());
        Assertions.assertTrue(SmtpReplyCode.CODE_452.isTransient());
    }

    @Test
    void permanentAndTransientAreMutuallyExclusive() {
        for (SmtpReplyCode code : SmtpReplyCode.values()) {
            Assertions.assertNotEquals(code.isPermanent(), code.isTransient(),
                    "Code " + code.getCode() + " should be either permanent or transient, not both");
        }
    }

    @Test
    void fromContentFinds550() {
        Optional<SmtpReplyCode> result = SmtpReplyCode.fromContent(
                "smtp; 550 5.1.1 The email account that you tried to reach does not exist.");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(SmtpReplyCode.CODE_550, result.get());
    }

    @Test
    void fromContentFinds421() {
        Optional<SmtpReplyCode> result = SmtpReplyCode.fromContent(
                "421 Service temporarily unavailable, try again later.");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(SmtpReplyCode.CODE_421, result.get());
    }

    @Test
    void fromContentReturnsFirstMatch() {
        // Content contains both 421 and 550 — should return whichever enum is listed first (421)
        Optional<SmtpReplyCode> result = SmtpReplyCode.fromContent("421 then later 550");
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(SmtpReplyCode.CODE_421, result.get());
    }

    @Test
    void fromContentReturnsEmptyForNoMatch() {
        Optional<SmtpReplyCode> result = SmtpReplyCode.fromContent("Everything is fine, code 200");
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void fromContentReturnsEmptyForNull() {
        Optional<SmtpReplyCode> result = SmtpReplyCode.fromContent(null);
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void fromContentReturnsEmptyForBlank() {
        Optional<SmtpReplyCode> result = SmtpReplyCode.fromContent("");
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void codeAndDescriptionPopulated() {
        Assertions.assertEquals("550", SmtpReplyCode.CODE_550.getCode());
        Assertions.assertNotNull(SmtpReplyCode.CODE_550.getDescription());
        Assertions.assertFalse(SmtpReplyCode.CODE_550.getDescription().isEmpty());
    }
}
