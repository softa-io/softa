package io.softa.framework.orm.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdUtilsTest {

    @Test
    void convertIdToLong() {
        assertNull(IdUtils.convertIdToLong(null));
        assertEquals(1L, IdUtils.convertIdToLong(1));
        assertEquals(1L, IdUtils.convertIdToLong(1));
        assertEquals(1L, IdUtils.convertIdToLong("1"));
        assertEquals(1L, IdUtils.convertIdToLong(1L));
    }
}