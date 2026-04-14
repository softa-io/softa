package io.softa.starter.message.mail.support;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BounceInfoTest {

    @Test
    void defaultValuesAreNullOrFalse() {
        BounceInfo info = new BounceInfo();
        Assertions.assertNull(info.getOriginalMessageId());
        Assertions.assertNull(info.getSmtpReplyCode());
        Assertions.assertNull(info.getEnhancedStatusCode());
        Assertions.assertNull(info.getDiagnosticMessage());
        Assertions.assertNull(info.getAction());
        Assertions.assertNull(info.getFailedRecipients());
        Assertions.assertFalse(info.isPermanent());
    }

    @Test
    void permanentFailureFlag() {
        BounceInfo info = new BounceInfo();
        info.setPermanent(true);
        Assertions.assertTrue(info.isPermanent());

        info.setPermanent(false);
        Assertions.assertFalse(info.isPermanent());
    }

    @Test
    void failedRecipientsStoredCorrectly() {
        BounceInfo info = new BounceInfo();
        List<String> recipients = List.of("user1@example.com", "user2@example.com");
        info.setFailedRecipients(recipients);
        Assertions.assertEquals(2, info.getFailedRecipients().size());
        Assertions.assertEquals("user1@example.com", info.getFailedRecipients().getFirst());
    }

    @Test
    void fullBounceInfoPopulation() {
        BounceInfo info = new BounceInfo();
        info.setOriginalMessageId("<abc@example.com>");
        info.setSmtpReplyCode("550");
        info.setEnhancedStatusCode("5.1.1");
        info.setDiagnosticMessage("smtp; 550 5.1.1 User unknown");
        info.setAction("failed");
        info.setFailedRecipients(List.of("unknown@example.com"));
        info.setPermanent(true);

        Assertions.assertEquals("<abc@example.com>", info.getOriginalMessageId());
        Assertions.assertEquals("550", info.getSmtpReplyCode());
        Assertions.assertEquals("5.1.1", info.getEnhancedStatusCode());
        Assertions.assertEquals("smtp; 550 5.1.1 User unknown", info.getDiagnosticMessage());
        Assertions.assertEquals("failed", info.getAction());
        Assertions.assertEquals(1, info.getFailedRecipients().size());
        Assertions.assertTrue(info.isPermanent());
    }
}
