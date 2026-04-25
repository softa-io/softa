package io.softa.framework.base.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = I18nJSONLoader.class)
class I18nTest {

    @Test
    void get() {
        String trans = I18n.get("Hello world!");
        Assertions.assertNotNull(trans);
    }

    @Test
    void testGet() {
    }

    @Test
    void numericArgsAreNotGrouped() {
        String msg = I18n.get("Env {0} is busy", 829273278605463553L);
        Assertions.assertEquals("Env 829273278605463553 is busy", msg);
    }
}