package io.softa.framework.orm.meta;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.jdbc.pipeline.DataCreatePipeline;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.orm.sequence.SequenceServiceHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;

/**
 * A create numbers a read-only {@code autoSequence} field from its sequence: read-only paired with
 * {@code autoSequence} is strict system numbering, not a field the create leaves out. Built on a real
 * metadata snapshot, so the field list the create pipeline takes from {@link ModelManager} is the one under
 * test. In this package for the metadata setters, as the other snapshot-built tests are.
 */
class ReadOnlyAutoSequenceCreateTest {

    private static final String MODEL = "SeqClaim";

    private final SequenceService sequenceService = Mockito.mock(SequenceService.class);
    private Object previousSnapshot;
    private SequenceService previousService;

    @BeforeEach
    void setUp() throws Exception {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        previousSnapshot = snapshotField().get(null);
        previousService = SequenceServiceHolder.get();
        SequenceServiceHolder.set(sequenceService);
        Mockito.when(sequenceService.next(eq(MODEL + ".claimNo"), anyMap())).thenReturn("ER-10000");
        Mockito.when(sequenceService.nextBatch(eq(MODEL + ".claimNo"), anyList(), Mockito.anyCollection()))
                .thenReturn(List.of("ER-10000", "ER-10001"));

        MetaModel model = new MetaModel();
        model.setModelName(MODEL);
        model.setLabel(MODEL);
        model.setTableName("seq_claim");
        JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
        Mockito.when(jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null))
                .thenReturn(new ArrayList<>(List.of(model)));
        Mockito.when(jdbcService.selectMetaEntityList("SysField", MetaField.class, null))
                .thenReturn(new ArrayList<>(List.of(
                        field("id", FieldType.LONG, false, false),
                        field("name", FieldType.STRING, false, false),
                        field("claimNo", FieldType.STRING, true, true),
                        field("note", FieldType.STRING, true, false))));
        ModelManager modelManager = new ModelManager();
        Field jdbc = ModelManager.class.getDeclaredField("jdbcService");
        jdbc.setAccessible(true);
        jdbc.set(modelManager, jdbcService);
        modelManager.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        snapshotField().set(null, previousSnapshot);
        if (previousService == null) {
            SequenceServiceHolder.clear();
        } else {
            SequenceServiceHolder.set(previousService);
        }
    }

    private static Field snapshotField() throws Exception {
        Field field = ModelManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return field;
    }

    private static MetaField field(String name, FieldType type, boolean readonly, boolean autoSequence) {
        MetaField metaField = new MetaField();
        metaField.setModelName(MODEL);
        metaField.setFieldName(name);
        metaField.setColumnName(name);
        metaField.setLabel(name);
        metaField.setFieldType(type);
        metaField.setReadonly(readonly);
        metaField.setAutoSequence(autoSequence);
        return metaField;
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void aReadOnlySequenceFieldIsNumberedAndStored() {
        DataCreatePipeline pipeline = new DataCreatePipeline(MODEL);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("name", "Taxi")));

        pipeline.processCreateData(rows, LocalDateTime.now());

        assertEquals("ER-10000", rows.getFirst().get("claimNo"));
        assertTrue(pipeline.getStoredFields().contains("claimNo"));
    }

    @Test
    void aBatchGetsOneNumberPerRow() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("name", "Taxi"), row("name", "Hotel")));

        new DataCreatePipeline(MODEL).processCreateData(rows, LocalDateTime.now());

        assertEquals("ER-10000", rows.get(0).get("claimNo"));
        assertEquals("ER-10001", rows.get(1).get("claimNo"));
    }

    @Test
    void aCallerCannotGiveTheNumber() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("name", "Taxi", "claimNo", "MINE-1")));

        assertThrows(RuntimeException.class,
                () -> new DataCreatePipeline(MODEL).processCreateData(rows, LocalDateTime.now()));
    }

    @Test
    void aReadOnlyFieldWithoutASequenceStaysOutOfTheCreate() {
        assertFalse(new DataCreatePipeline(MODEL).getStoredFields().contains("note"));
        assertTrue(ModelManager.getModelCreatableFields(MODEL).contains("claimNo"));
        assertFalse(ModelManager.getModelUpdatableFields(MODEL).contains("claimNo"));
    }
}
