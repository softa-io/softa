package io.softa.framework.orm.utils;

import me.ahoo.cosid.provider.DefaultIdGeneratorProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IDGeneratorTest {

    @BeforeAll
    static void setup() {
        DefaultIdGeneratorProvider.INSTANCE.setShare(MockIdGenerator.INSTANCE);
    }

    @Test
    void generateLongId() {
        long id = IDGenerator.generateLongId();
        Assertions.assertTrue(id > 0);
    }

    @Test
    void generateStringId() {
        String id = IDGenerator.generateStringId();
        Assertions.assertNotNull(id);
        Assertions.assertFalse(id.isEmpty());
    }

    @Test
    void generateStringIdBase36() {
        String id = IDGenerator.generateStringIdBase36();
        Assertions.assertNotNull(id);
        Assertions.assertFalse(id.isEmpty());
    }

    @Test
    void generateStringIdBase62() {
        String id = IDGenerator.generateStringIdBase62();
        Assertions.assertNotNull(id);
        Assertions.assertFalse(id.isEmpty());
    }
}