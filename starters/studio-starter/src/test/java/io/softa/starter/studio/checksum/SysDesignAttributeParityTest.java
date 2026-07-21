package io.softa.starter.studio.checksum;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.checksum.AggregateChecksum;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Structural mirror guard for EVERY cross-lane checksum attribute, 
 * generalizing {@link SysDesignIndexMirrorTest} (which pins the single index {@code message} attribute) to the full attribute lists.
 *
 * <p>{@code AggregateChecksum}'s attribute lists are derived from the {@code Sys*} entities alone, 
 * and {@code CanonicalMetadataSerializer} hashes an absent attribute exactly like a null one 
 * — so a {@code @Field} added on the runtime side only slips through the value-based golden test (both fixtures hash '∅') 
 * while every real deployment diverges permanently: runtime rows carry a value, design rows can never carry the attribute at all. 
 * This happened with {@code SysField.autoSequence}; 
 * this test makes the NEXT one-sided addition fail the build instead.
 */
class SysDesignAttributeParityTest {

    @Test
    void everyChecksumAttributeHasASameTypedDesignTwin() {
        assertParity(AggregateChecksum.MODEL_ATTRS, SysModel.class, DesignModel.class);
        assertParity(AggregateChecksum.FIELD_ATTRS, SysField.class, DesignField.class);
        assertParity(AggregateChecksum.INDEX_ATTRS, SysModelIndex.class, DesignModelIndex.class);
        assertParity(AggregateChecksum.OPTION_SET_ATTRS, SysOptionSet.class, DesignOptionSet.class);
        assertParity(AggregateChecksum.OPTION_ITEM_ATTRS, SysOptionItem.class, DesignOptionItem.class);
    }

    private static void assertParity(List<String> attrs, Class<?> sysType, Class<?> designType) {
        for (String attr : attrs) {
            Field sys = fieldOf(sysType, attr);
            Field design = fieldOf(designType, attr);
            assertEquals(sys.getType(), design.getType(),
                    designType.getSimpleName() + "." + attr
                            + " must have the same Java type as " + sysType.getSimpleName()
                            + "." + attr + " — the cross-lane checksum serializes both by value");
        }
    }

    private static Field fieldOf(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new AssertionError(type.getSimpleName() + " must declare '" + name
                + "' — it is a cross-lane checksum attribute (derived from the Sys* entity), "
                + "and a missing twin makes every deploy see phantom drift on this attribute");
    }
}
