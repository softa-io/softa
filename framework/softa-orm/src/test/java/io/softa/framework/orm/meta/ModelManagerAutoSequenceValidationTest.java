package io.softa.framework.orm.meta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.StorageType;
import io.softa.framework.orm.jdbc.JdbcService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ModelManager.verifyAutoSequenceAttribute} coverage. The annotation
 * parser enforces the same rules for the annotation lane at scan time; this
 * gate is what protects the studio no-code lane and hand-written sys_field
 * rows, so it is tested at init() level with raw metadata fixtures.
 */
class ModelManagerAutoSequenceValidationTest {

    private static Object previousSnapshot;

    @BeforeAll
    static void rememberSnapshot() throws Exception {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        previousSnapshot = snapshotField().get(null);
    }

    @AfterAll
    static void restoreSnapshot() throws Exception {
        snapshotField().set(null, previousSnapshot);
    }

    private static Field snapshotField() throws Exception {
        Field field = ModelManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return field;
    }

    // ---- fixtures ----------------------------------------------------------

    private static MetaModel model(String modelName, StorageType storageType) {
        MetaModel metaModel = new MetaModel();
        metaModel.setModelName(modelName);
        metaModel.setLabel(modelName);
        metaModel.setTableName("seq_doc");
        metaModel.setStorageType(storageType);
        return metaModel;
    }

    private static MetaField field(String modelName, String fieldName, FieldType type, boolean autoSequence) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setColumnName(fieldName);
        metaField.setLabel(fieldName);
        metaField.setFieldType(type);
        metaField.setAutoSequence(autoSequence);
        return metaField;
    }

    private static void initWith(MetaModel metaModel, List<MetaField> fields) throws Exception {
        JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
        Mockito.when(jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null))
                .thenReturn(new ArrayList<>(List.of(metaModel)));
        Mockito.when(jdbcService.selectMetaEntityList("SysField", MetaField.class, null))
                .thenReturn(new ArrayList<>(fields));
        ModelManager modelManager = new ModelManager();
        Field jdbc = ModelManager.class.getDeclaredField("jdbcService");
        jdbc.setAccessible(true);
        jdbc.set(modelManager, jdbcService);
        modelManager.init();
    }

    private static void assertInitRejected(MetaModel metaModel, List<MetaField> fields, String messagePart)
            throws Exception {
        Object goodSnapshot = snapshotField().get(null);
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> initWith(metaModel, fields));
            assertTrue(e.getMessage().contains(messagePart),
                    "expected message containing '" + messagePart + "' but was: " + e.getMessage());
        } finally {
            snapshotField().set(null, goodSnapshot);
        }
    }

    // ---- cases --------------------------------------------------------------

    @Test
    void stringFieldOnRdbmsModel_isAccepted_andFlagIsLoaded() throws Exception {
        Object goodSnapshot = snapshotField().get(null);
        try {
            initWith(model("SeqDoc", StorageType.RDBMS), List.of(
                    field("SeqDoc", "id", FieldType.LONG, false),
                    field("SeqDoc", "docNo", FieldType.STRING, true)));
            assertTrue(ModelManager.getModelField("SeqDoc", "docNo").isAutoSequence());
        } finally {
            snapshotField().set(null, goodSnapshot);
        }
    }

    @Test
    void unsetStorageType_isTreatedAsRdbmsDefault() throws Exception {
        Object goodSnapshot = snapshotField().get(null);
        try {
            initWith(model("SeqDoc", null), List.of(
                    field("SeqDoc", "id", FieldType.LONG, false),
                    field("SeqDoc", "docNo", FieldType.STRING, true)));
            assertTrue(ModelManager.getModelField("SeqDoc", "docNo").isAutoSequence());
        } finally {
            snapshotField().set(null, goodSnapshot);
        }
    }

    @Test
    void nonStringField_isRejected() throws Exception {
        assertInitRejected(model("SeqDoc", StorageType.RDBMS), List.of(
                        field("SeqDoc", "id", FieldType.LONG, false),
                        field("SeqDoc", "counter", FieldType.INTEGER, true)),
                "requires a STRING field");
    }

    @Test
    void dynamicField_isRejected() throws Exception {
        MetaField dynamic = field("SeqDoc", "docNo", FieldType.STRING, true);
        dynamic.setDynamic(true);
        assertInitRejected(model("SeqDoc", StorageType.RDBMS), List.of(
                        field("SeqDoc", "id", FieldType.LONG, false),
                        dynamic),
                "cannot be combined with computed or dynamic");
    }

    @Test
    void primaryKeyField_isRejected() throws Exception {
        assertInitRejected(model("SeqDoc", StorageType.RDBMS), List.of(
                        field("SeqDoc", "id", FieldType.STRING, true)),
                "not allowed on the primary key");
    }

    @Test
    void nonRdbmsStorage_isRejected() throws Exception {
        assertInitRejected(model("SeqDoc", StorageType.ES), List.of(
                        field("SeqDoc", "id", FieldType.LONG, false),
                        field("SeqDoc", "docNo", FieldType.STRING, true)),
                "requires RDBMS storage");
    }
}
