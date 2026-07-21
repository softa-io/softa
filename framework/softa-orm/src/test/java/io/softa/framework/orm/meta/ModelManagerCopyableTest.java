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
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.jdbc.JdbcService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Copy-semantics coverage for {@link ModelManager}: model-level
 * {@code isCopyableModel} and the field-level exclusions of
 * {@code getModelCopyableFields}. Builds a real frozen snapshot through
 * {@code init()} with a mocked {@link JdbcService} (instead of stubbing
 * statics) because both methods read snapshot internals directly.
 */
class ModelManagerCopyableTest {

    private static Object previousSnapshot;

    @BeforeAll
    static void initSnapshot() throws Exception {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        previousSnapshot = snapshotField().get(null);

        JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
        Mockito.when(jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null))
                .thenReturn(models());
        Mockito.when(jdbcService.selectMetaEntityList("SysField", MetaField.class, null))
                .thenReturn(fields());

        ModelManager modelManager = new ModelManager();
        Field jdbc = ModelManager.class.getDeclaredField("jdbcService");
        jdbc.setAccessible(true);
        jdbc.set(modelManager, jdbcService);
        modelManager.init();
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

    // ---- fixture ---------------------------------------------------------

    private static List<MetaModel> models() {
        // ArrayList, not List.of: ListUtils.allNotNull probes contains(null),
        // which immutable collections reject with NPE.
        return new ArrayList<>(List.of(
                model("CopyDoc", "copy_doc", true, false),
                model("CopyProfile", "copy_profile", true, false),
                model("CopyLine", "copy_line", true, false),
                model("CopyAudit", "copy_audit", false, false),
                timelineModel()));
    }

    private static List<MetaField> fields() {
        return new ArrayList<>(List.of(
                // CopyDoc — the model under test
                field("CopyDoc", "id", "id", FieldType.LONG),
                field("CopyDoc", "name", "name", FieldType.STRING),
                copyableFalseField("CopyDoc", "code", "code", FieldType.STRING),
                field("CopyDoc", "externalId", "external_id", FieldType.STRING),
                field("CopyDoc", "createdTime", "created_time", FieldType.DATE_TIME),
                oneToOne("CopyDoc", "profileId", "profile_id", "CopyProfile"),
                oneToMany("CopyDoc", "lines", "CopyLine", "docId"),
                dynamicField("CopyDoc", "summary", "summary", FieldType.STRING),
                autoSequenceField("CopyDoc", "docNo", "doc_no"),
                // related models
                field("CopyProfile", "id", "id", FieldType.LONG),
                field("CopyLine", "id", "id", FieldType.LONG),
                manyToOne("CopyLine", "docId", "doc_id", "CopyDoc"),
                // model-level non-copyable
                field("CopyAudit", "id", "id", FieldType.LONG),
                field("CopyAudit", "message", "message", FieldType.STRING),
                // timeline model
                field("CopyTimeline", "id", "id", FieldType.LONG),
                field("CopyTimeline", "sliceId", "slice_id", FieldType.LONG),
                field("CopyTimeline", "effectiveStartDate", "effective_start_date", FieldType.DATE),
                field("CopyTimeline", "effectiveEndDate", "effective_end_date", FieldType.DATE),
                field("CopyTimeline", "name", "name", FieldType.STRING)));
    }

    private static MetaModel model(String modelName, String tableName, boolean copyable, boolean timeline) {
        MetaModel metaModel = new MetaModel();
        metaModel.setModelName(modelName);
        metaModel.setLabel(modelName);
        metaModel.setTableName(tableName);
        metaModel.setCopyable(copyable);
        metaModel.setTimeline(timeline);
        return metaModel;
    }

    private static MetaModel timelineModel() {
        MetaModel metaModel = model("CopyTimeline", "copy_timeline", true, true);
        // Timeline models require an app-generated logical id (boot-guarded): the
        // auto-increment lands on the physical sliceId, not the shared `id` column.
        metaModel.setIdStrategy(IdStrategy.DISTRIBUTED_LONG);
        return metaModel;
    }

    @Test
    void timelineModelWithDbAutoIdIsRejectedAtInit() throws Exception {
        Object goodSnapshot = snapshotField().get(null);
        try {
            // DB_AUTO_ID (the null-default) cannot fill the shared logical `id` column.
            MetaModel badTimeline = model("BadTimeline", "bad_timeline", true, true);
            JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
            Mockito.when(jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null))
                    .thenReturn(new ArrayList<>(List.of(badTimeline)));
            Mockito.when(jdbcService.selectMetaEntityList("SysField", MetaField.class, null))
                    .thenReturn(new ArrayList<>(List.of(
                            field("BadTimeline", "id", "id", FieldType.LONG),
                            field("BadTimeline", "sliceId", "slice_id", FieldType.LONG),
                            field("BadTimeline", "effectiveStartDate", "effective_start_date", FieldType.DATE),
                            field("BadTimeline", "effectiveEndDate", "effective_end_date", FieldType.DATE))));
            ModelManager modelManager = new ModelManager();
            Field jdbc = ModelManager.class.getDeclaredField("jdbcService");
            jdbc.setAccessible(true);
            jdbc.set(modelManager, jdbcService);
            RuntimeException e = assertThrows(RuntimeException.class, modelManager::init);
            assertTrue(e.getMessage().contains("app-generated logical id"));
        } finally {
            snapshotField().set(null, goodSnapshot);
        }
    }

    private static MetaField field(String modelName, String fieldName, String columnName, FieldType type) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setColumnName(columnName);
        metaField.setLabel(fieldName);
        metaField.setFieldType(type);
        return metaField;
    }

    private static MetaField copyableFalseField(String modelName, String fieldName, String columnName, FieldType type) {
        MetaField metaField = field(modelName, fieldName, columnName, type);
        metaField.setCopyable(false);
        return metaField;
    }

    private static MetaField dynamicField(String modelName, String fieldName, String columnName, FieldType type) {
        MetaField metaField = field(modelName, fieldName, columnName, type);
        metaField.setDynamic(true);
        return metaField;
    }

    private static MetaField autoSequenceField(String modelName, String fieldName, String columnName) {
        MetaField metaField = field(modelName, fieldName, columnName, FieldType.STRING);
        metaField.setAutoSequence(true);
        return metaField;
    }

    private static MetaField oneToOne(String modelName, String fieldName, String columnName, String relatedModel) {
        MetaField metaField = field(modelName, fieldName, columnName, FieldType.ONE_TO_ONE);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    private static MetaField manyToOne(String modelName, String fieldName, String columnName, String relatedModel) {
        MetaField metaField = field(modelName, fieldName, columnName, FieldType.MANY_TO_ONE);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    private static MetaField oneToMany(String modelName, String fieldName, String relatedModel, String relatedField) {
        MetaField metaField = field(modelName, fieldName, fieldName, FieldType.ONE_TO_MANY);
        metaField.setRelatedModel(relatedModel);
        metaField.setRelatedField(relatedField);
        return metaField;
    }

    // ---- model level -----------------------------------------------------

    @Test
    void isCopyableModel_followsModelAttribute() {
        assertTrue(ModelManager.isCopyableModel("CopyDoc"));
        assertFalse(ModelManager.isCopyableModel("CopyAudit"));
    }

    @Test
    void copyableDefaultsToTrue_onProgrammaticallyConstructedModel() {
        assertTrue(new MetaModel().isCopyable());
        assertTrue(new MetaField().isCopyable());
    }

    // ---- field level -----------------------------------------------------

    @Test
    void getModelCopyableFields_excludesStructuralAndDeclaredFields() {
        List<String> copyable = ModelManager.getModelCopyableFields("CopyDoc");
        assertEquals(List.of("name"), copyable,
                "expected: id/externalId/createdTime (structural & audit), code (copyable=false), "
                        + "profileId (OneToOne), lines (OneToMany/dynamic), summary (dynamic), "
                        + "docNo (autoSequence — a copy gets a fresh number on insert) all excluded");
    }

    @Test
    void getModelCopyableFields_manyToOneFkRemainsCopyable() {
        List<String> copyable = ModelManager.getModelCopyableFields("CopyLine");
        assertTrue(copyable.contains("docId"), "ManyToOne FK is shared-reference semantics — stays copyable");
        assertFalse(copyable.contains("id"));
    }

    @Test
    void getModelCopyableFields_timelineDropsAllStructuralKeys() {
        // A copy is a NEW entity: excluding id (as well as sliceId + effective dates) makes
        // createSlices generate a fresh logical id and a genesis slice at the current date,
        // instead of grafting a spurious slice onto the source entity's own timeline.
        List<String> copyable = ModelManager.getModelCopyableFields("CopyTimeline");
        assertFalse(copyable.contains("id"), "a copy must not carry the source's logical id");
        assertFalse(copyable.contains("sliceId"));
        assertFalse(copyable.contains("effectiveStartDate"));
        assertFalse(copyable.contains("effectiveEndDate"));
        assertTrue(copyable.contains("name"), "business fields stay copyable");
    }
}
