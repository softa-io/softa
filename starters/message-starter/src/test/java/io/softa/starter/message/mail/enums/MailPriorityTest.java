package io.softa.starter.message.mail.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MailPriorityTest {

    @Test
    void highPriorityHeaderValues() {
        MailPriority p = MailPriority.HIGH;
        Assertions.assertEquals("High", p.getCode());
        Assertions.assertEquals("1", p.getXPriority());
        Assertions.assertEquals("High", p.getImportance());
        Assertions.assertEquals("High", p.getXMsMailPriority());
    }

    @Test
    void normalPriorityHeaderValues() {
        MailPriority p = MailPriority.NORMAL;
        Assertions.assertEquals("Normal", p.getCode());
        Assertions.assertEquals("3", p.getXPriority());
        Assertions.assertEquals("Normal", p.getImportance());
        Assertions.assertEquals("Normal", p.getXMsMailPriority());
    }

    @Test
    void lowPriorityHeaderValues() {
        MailPriority p = MailPriority.LOW;
        Assertions.assertEquals("Low", p.getCode());
        Assertions.assertEquals("5", p.getXPriority());
        Assertions.assertEquals("Low", p.getImportance());
        Assertions.assertEquals("Low", p.getXMsMailPriority());
    }

    @Test
    void valueOfResolvesCorrectly() {
        Assertions.assertEquals(MailPriority.HIGH, MailPriority.valueOf("HIGH"));
        Assertions.assertEquals(MailPriority.NORMAL, MailPriority.valueOf("NORMAL"));
        Assertions.assertEquals(MailPriority.LOW, MailPriority.valueOf("LOW"));
    }

    @Test
    void valueOfInvalidThrowsException() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MailPriority.valueOf("URGENT"));
    }

    @Test
    void allValuesHaveDistinctXPriority() {
        Assertions.assertEquals(3, MailPriority.values().length);
        Assertions.assertNotEquals(MailPriority.HIGH.getXPriority(), MailPriority.NORMAL.getXPriority());
        Assertions.assertNotEquals(MailPriority.NORMAL.getXPriority(), MailPriority.LOW.getXPriority());
    }
}
