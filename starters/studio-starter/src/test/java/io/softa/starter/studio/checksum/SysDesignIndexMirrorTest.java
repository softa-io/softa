package io.softa.starter.studio.checksum;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.softa.framework.orm.enums.IndexType;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.studio.meta.entity.DesignModelIndex;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural mirror guard for the index attributes that only one lane derives from.
 *
 * <p>{@code INDEX_ATTRS} is derived from {@code SysModelIndex} alone, so a value-only golden
 * fixture is one-directional: if an attribute were added to {@code DesignModelIndex} but
 * forgotten on {@code SysModelIndex}, it would be projected away on BOTH sides and the
 * cross-lane equality would still pass while the studio value silently never shipped. This
 * test closes both directions by asserting each entity declares the attribute as a
 * {@code @Field} of identical Java type.
 */
class SysDesignIndexMirrorTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "message",
            "indexType",
    })
    void bothLanesDeclareAttributeOfSameType(String attribute) {
        Field sys = fieldOf(SysModelIndex.class, attribute);
        Field design = fieldOf(DesignModelIndex.class, attribute);
        assertNotNull(sys.getAnnotation(io.softa.framework.orm.annotation.Field.class),
                "SysModelIndex." + attribute + " must carry @Field");
        assertNotNull(design.getAnnotation(io.softa.framework.orm.annotation.Field.class),
                "DesignModelIndex." + attribute + " must carry @Field");
        assertEquals(sys.getType(), design.getType(),
                attribute + " field type must match across the runtime and studio lanes");
    }

    @Test
    void mirroredAttributesKeepTheirExpectedTypes() {
        assertEquals(String.class, fieldOf(SysModelIndex.class, "message").getType());
        assertEquals(IndexType.class, fieldOf(SysModelIndex.class, "indexType").getType());
    }

    private static Field fieldOf(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(type.getSimpleName() + " must declare a '" + name + "' field", e);
        }
    }
}
