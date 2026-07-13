package io.softa.starter.message.mail.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReceivedMailTypeTest {

    @Test
    void normalTypeCode() {
        Assertions.assertEquals("Normal", ReceivedMailType.NORMAL.getCode());
    }

    @Test
    void readReceiptTypeCode() {
        Assertions.assertEquals("ReadReceipt", ReceivedMailType.READ_RECEIPT.getCode());
    }

    @Test
    void bounceTypeCode() {
        Assertions.assertEquals("Bounce", ReceivedMailType.BOUNCE.getCode());
    }

    @Test
    void allValuesHaveDistinctCodes() {
        ReceivedMailType[] types = ReceivedMailType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                Assertions.assertNotEquals(types[i].getCode(), types[j].getCode(),
                        types[i].name() + " and " + types[j].name() + " should have distinct codes");
            }
        }
    }

    @Test
    void exactlySixValues() {
        // NORMAL, READ_RECEIPT, BOUNCE, AUTO_REPLY, CALENDAR_INVITE, UNKNOWN.
        // Mutually-exclusive content types only — orthogonal properties
        // (mailing-list, encrypted, spam) are boolean flags on
        // MailReceiveRecord, not enum values. New mutually-exclusive content
        // types should be added here AND wired into MailClassifier rules.
        Assertions.assertEquals(6, ReceivedMailType.values().length);
    }
}
