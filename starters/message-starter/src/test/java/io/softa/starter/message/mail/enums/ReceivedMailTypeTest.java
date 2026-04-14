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
    void allValuesHaveDescription() {
        for (ReceivedMailType type : ReceivedMailType.values()) {
            Assertions.assertNotNull(type.getDescription());
            Assertions.assertFalse(type.getDescription().isEmpty(),
                    type.name() + " should have a non-empty description");
        }
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
    void exactlyThreeValues() {
        Assertions.assertEquals(3, ReceivedMailType.values().length);
    }
}
