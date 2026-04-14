package io.softa.starter.message.mail.support;

import io.softa.starter.message.mail.enums.ReceivedMailType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class MailClassificationTest {

    @Test
    void normalCreatesCorrectType() {
        MailClassification c = MailClassification.normal();
        Assertions.assertEquals(ReceivedMailType.NORMAL, c.getType());
        Assertions.assertNull(c.getOriginalMessageId());
        Assertions.assertNull(c.getBounceInfo());
    }

    @Test
    void readReceiptSetsTypeAndMessageId() {
        String msgId = "<original@example.com>";
        MailClassification c = MailClassification.readReceipt(msgId);
        Assertions.assertEquals(ReceivedMailType.READ_RECEIPT, c.getType());
        Assertions.assertEquals(msgId, c.getOriginalMessageId());
        Assertions.assertNull(c.getBounceInfo());
    }

    @Test
    void readReceiptWithNullMessageId() {
        MailClassification c = MailClassification.readReceipt(null);
        Assertions.assertEquals(ReceivedMailType.READ_RECEIPT, c.getType());
        Assertions.assertNull(c.getOriginalMessageId());
    }

    @Test
    void bounceSetsTypeAndBounceInfo() {
        BounceInfo info = new BounceInfo();
        info.setOriginalMessageId("<bounced@example.com>");
        info.setSmtpReplyCode("550");
        info.setEnhancedStatusCode("5.1.1");
        info.setPermanent(true);
        info.setFailedRecipients(List.of("user@example.com"));

        MailClassification c = MailClassification.bounce(info);
        Assertions.assertEquals(ReceivedMailType.BOUNCE, c.getType());
        Assertions.assertEquals("<bounced@example.com>", c.getOriginalMessageId());
        Assertions.assertNotNull(c.getBounceInfo());
        Assertions.assertEquals("550", c.getBounceInfo().getSmtpReplyCode());
        Assertions.assertEquals("5.1.1", c.getBounceInfo().getEnhancedStatusCode());
        Assertions.assertTrue(c.getBounceInfo().isPermanent());
    }

    @Test
    void bounceWithNullBounceInfo() {
        MailClassification c = MailClassification.bounce(null);
        Assertions.assertEquals(ReceivedMailType.BOUNCE, c.getType());
        Assertions.assertNull(c.getOriginalMessageId());
        Assertions.assertNull(c.getBounceInfo());
    }

    @Test
    void bounceOriginalMessageIdFromBounceInfo() {
        BounceInfo info = new BounceInfo();
        info.setOriginalMessageId("<test-id@mail.com>");

        MailClassification c = MailClassification.bounce(info);
        Assertions.assertEquals("<test-id@mail.com>", c.getOriginalMessageId());
    }
}
