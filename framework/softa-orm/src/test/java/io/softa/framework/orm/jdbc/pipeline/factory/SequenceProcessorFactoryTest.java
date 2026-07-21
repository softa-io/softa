package io.softa.framework.orm.jdbc.pipeline.factory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.pipeline.processor.SequenceProcessor;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.orm.sequence.SequenceServiceHolder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SequenceProcessorFactory} gating: inactive off-CREATE and for
 * un-flagged fields, active when a {@link SequenceService} is registered, and
 * loud (not silently inactive) when the flag is present but no implementation
 * is on the classpath — a deployment error must fail the insert, not fall
 * through to the static default value.
 */
class SequenceProcessorFactoryTest {

    private final SequenceProcessorFactory factory = new SequenceProcessorFactory();

    @BeforeEach
    @AfterEach
    void resetHolder() {
        SequenceServiceHolder.clear();
    }

    private static MetaField field(boolean autoSequence) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", "Emp");
        ReflectionTestUtils.setField(metaField, "fieldName", "code");
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "autoSequence", autoSequence);
        return metaField;
    }

    @Test
    void inactive_offCreate_andForUnflaggedFields() {
        SequenceServiceHolder.set(Mockito.mock(SequenceService.class));
        assertNull(factory.createProcessor(field(true), AccessType.UPDATE));
        assertNull(factory.createProcessor(field(false), AccessType.CREATE));
    }

    @Test
    void active_whenServiceRegistered() {
        SequenceServiceHolder.set(Mockito.mock(SequenceService.class));
        assertInstanceOf(SequenceProcessor.class, factory.createProcessor(field(true), AccessType.CREATE));
    }

    @Test
    void flaggedFieldWithoutImplementation_failsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> factory.createProcessor(field(true), AccessType.CREATE));
        assertTrue(e.getMessage().contains("Emp.code"));
        assertTrue(e.getMessage().contains("no SequenceService implementation"));
    }
}
