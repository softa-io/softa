package io.softa.framework.orm.jdbc.database.builder;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrow-field SELECTs must round-trip the write keys: {@code id} always, {@code version}
 * when optimistic locking is on, and — for timeline models — the physical {@code sliceId}
 * (updates/deletes of a version are keyed by it, e.g. rows of the version-list API).
 */
class SelectBuilderTest {

    private static final String MODEL = "DeptInfo";

    @Test
    void narrowFieldSelectOnTimelineModelRoundTripsSliceId() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, true);

            SqlWrapper sqlWrapper = new SqlWrapper(MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setFields(List.of("name"));
            new SelectBuilder(sqlWrapper, flexQuery).build();

            assertTrue(flexQuery.getFields().contains(ModelConstant.SLICE_ID),
                    "timeline narrow selects must carry sliceId, got: " + flexQuery.getFields());
            assertTrue(flexQuery.getFields().contains(ModelConstant.ID));
        }
    }

    @Test
    void narrowFieldSelectOnRegularModelDoesNotAddSliceId() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, false);

            SqlWrapper sqlWrapper = new SqlWrapper(MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setFields(List.of("name"));
            new SelectBuilder(sqlWrapper, flexQuery).build();

            assertFalse(flexQuery.getFields().contains(ModelConstant.SLICE_ID));
            assertTrue(flexQuery.getFields().contains(ModelConstant.ID));
        }
    }

    private void stubModel(MockedStatic<ModelManager> mm, boolean timeline) {
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", MODEL);
        ReflectionTestUtils.setField(metaModel, "tableName", "dept_info");
        mm.when(() -> ModelManager.getModel(MODEL)).thenReturn(metaModel);
        mm.when(() -> ModelManager.isTimelineModel(MODEL)).thenReturn(timeline);
        mm.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(false);
        stubField(mm, "name", "name", FieldType.STRING);
        stubField(mm, ModelConstant.ID, "id", FieldType.LONG);
        if (timeline) {
            stubField(mm, ModelConstant.SLICE_ID, "slice_id", FieldType.LONG);
        }
    }

    private void stubField(MockedStatic<ModelManager> mm, String fieldName, String columnName, FieldType type) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "columnName", columnName);
        ReflectionTestUtils.setField(metaField, "fieldType", type);
        mm.when(() -> ModelManager.getModelField(MODEL, fieldName)).thenReturn(metaField);
        mm.when(() -> ModelManager.existField(MODEL, fieldName)).thenReturn(true);
        mm.when(() -> ModelManager.isStored(MODEL, fieldName)).thenReturn(true);
    }
}
