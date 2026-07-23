package io.softa.framework.orm.jdbc.database;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The UPDATE statement must carry COLUMN names on both sides. The WHERE pk of a timeline
 * model is the one case where the pk FIELD name ({@code sliceId}) differs from its column
 * ({@code slice_id}) — regular models (pk {@code id}) masked the asymmetry.
 */
class StaticSqlBuilderTest {

    @Test
    void updateSqlWherePkUsesTheColumnNameForTimelineModels() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "DeptInfo", "dept_info", ModelConstant.SLICE_ID);
            stubField(mm, "DeptInfo", ModelConstant.SLICE_ID, "slice_id");
            stubField(mm, "DeptInfo", "name", "name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.SLICE_ID, 11L);
            row.put("name", "R&D");

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("DeptInfo", row);

            assertEquals("UPDATE dept_info SET name=? WHERE slice_id = ?", sqlParams.getSql());
            assertEquals("R&D", sqlParams.getArgs().get(0));
            assertEquals(11L, sqlParams.getArgs().get(1));
        }
    }

    @Test
    void updateSqlWherePkStaysIdForRegularModels() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "EmpInfo", "emp_info", ModelConstant.ID);
            stubField(mm, "EmpInfo", ModelConstant.ID, "id");
            stubField(mm, "EmpInfo", "name", "name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.ID, 6L);
            row.put("name", "Tom");

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("EmpInfo", row);

            assertEquals("UPDATE emp_info SET name=? WHERE id = ?", sqlParams.getSql());
        }
    }

    private void stubModel(MockedStatic<ModelManager> mm, String modelName, String tableName, String pk) {
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", modelName);
        ReflectionTestUtils.setField(metaModel, "tableName", tableName);
        mm.when(() -> ModelManager.getModel(modelName)).thenReturn(metaModel);
        mm.when(() -> ModelManager.getModelPrimaryKey(modelName)).thenReturn(pk);
    }

    private void stubField(MockedStatic<ModelManager> mm, String modelName, String fieldName, String columnName) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", modelName);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "columnName", columnName);
        mm.when(() -> ModelManager.getModelField(modelName, fieldName)).thenReturn(metaField);
    }
}
