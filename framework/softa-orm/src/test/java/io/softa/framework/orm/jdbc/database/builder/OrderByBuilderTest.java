package io.softa.framework.orm.jdbc.database.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that ORDER BY supports dynamic cascaded field aliases
 * declared on the model (`dynamic=true && cascadedField`).
 * The alias must be auto-expanded to its underlying `a.b` cascade path,
 * producing `tN.column` plus the matching LEFT JOIN.
 */
class OrderByBuilderTest {

    private static final String MAIN_MODEL = "Employee";
    private static final String MAIN_TABLE = "employee";
    private static final String RELATED_MODEL = "User";
    private static final String RELATED_TABLE = "user_info";

    @Test
    void orderByDynamicCascadedAliasAscRewritesToUnderlyingPath() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubManagerNameCascade(mm);

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofAsc("mgrName"));

            new OrderByBuilder(sqlWrapper, flexQuery).build();

            assertEquals("t1.full_name ASC,", orderByClause(sqlWrapper));
            assertTrue(joinClause(sqlWrapper).contains("LEFT JOIN " + RELATED_TABLE + " t1"),
                    "expected LEFT JOIN to user_info, got: " + joinClause(sqlWrapper));
        }
    }

    @Test
    void orderByDynamicCascadedAliasDescRewritesToUnderlyingPath() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubManagerNameCascade(mm);

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofDesc("mgrName"));

            new OrderByBuilder(sqlWrapper, flexQuery).build();

            assertEquals("t1.full_name DESC,", orderByClause(sqlWrapper));
        }
    }

    @Test
    void orderByMixesDynamicCascadedAliasAndStoredField() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubManagerNameCascade(mm);
            stubStoredField(mm, MAIN_MODEL, "createdAt", "created_at");

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofAsc("mgrName").addDesc("createdAt"));

            new OrderByBuilder(sqlWrapper, flexQuery).build();

            assertEquals("t1.full_name ASC,t.created_at DESC,", orderByClause(sqlWrapper));
        }
    }

    @Test
    void cursorPageAppendsStableIdOrderAfterDynamicCascadedAlias() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubManagerNameCascade(mm);

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofAsc("mgrName"));
            Page<Object> page = new Page<>(1, 50, true, true);

            new OrderByBuilder(sqlWrapper, flexQuery, page).build();

            assertEquals("t1.full_name ASC,t.id ASC,", orderByClause(sqlWrapper));
        }
    }

    @Test
    void orderByDirectCascadePathStillWorks() {
        // Regression: passing the cascade path directly (`manager.name`) is unchanged
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubManagerNameCascade(mm);

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofAsc("manager.name"));

            new OrderByBuilder(sqlWrapper, flexQuery).build();

            assertEquals("t1.full_name ASC,", orderByClause(sqlWrapper));
        }
    }

    @Test
    void orderByDynamicCascadedAliasRecordsAccessOnAliasAndCascadePath() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubManagerNameCascade(mm);

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofAsc("mgrName"));

            new OrderByBuilder(sqlWrapper, flexQuery).build();

            @SuppressWarnings("unchecked")
            Map<String, Set<String>> accessed =
                    (Map<String, Set<String>>) ReflectionTestUtils.getField(sqlWrapper, "accessModelFields");
            assertTrue(accessed.get(MAIN_MODEL).contains("mgrName"),
                    "alias must be recorded on the main model for permission checks");
            assertTrue(accessed.get(MAIN_MODEL).contains("manager"),
                    "cascade head must be recorded on the main model");
            assertTrue(accessed.get(RELATED_MODEL).contains("name"),
                    "cascade tail must be recorded on the related model");
        }
    }

    // -- helpers --

    private static void stubMainModel(MockedStatic<ModelManager> mm) {
        MetaModel mainModel = new MetaModel();
        ReflectionTestUtils.setField(mainModel, "modelName", MAIN_MODEL);
        ReflectionTestUtils.setField(mainModel, "tableName", MAIN_TABLE);
        mm.when(() -> ModelManager.getModel(MAIN_MODEL)).thenReturn(mainModel);

        MetaModel relatedModel = new MetaModel();
        ReflectionTestUtils.setField(relatedModel, "modelName", RELATED_MODEL);
        ReflectionTestUtils.setField(relatedModel, "tableName", RELATED_TABLE);
        mm.when(() -> ModelManager.getModel(RELATED_MODEL)).thenReturn(relatedModel);

        mm.when(() -> ModelManager.isTimelineModel(Mockito.anyString())).thenReturn(false);
        mm.when(() -> ModelManager.isMultiTenantControl(Mockito.anyString())).thenReturn(false);
    }

    private static void stubManagerNameCascade(MockedStatic<ModelManager> mm) {
        // Dynamic cascaded alias: mgrName → manager.name (declared on the main model)
        MetaField mgrName = new MetaField();
        ReflectionTestUtils.setField(mgrName, "modelName", MAIN_MODEL);
        ReflectionTestUtils.setField(mgrName, "fieldName", "mgrName");
        ReflectionTestUtils.setField(mgrName, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(mgrName, "dynamic", true);
        ReflectionTestUtils.setField(mgrName, "cascadedField", "manager.name");
        mm.when(() -> ModelManager.existField(MAIN_MODEL, "mgrName")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(MAIN_MODEL, "mgrName")).thenReturn(mgrName);

        // ManyToOne field: manager → User
        MetaField manager = new MetaField();
        ReflectionTestUtils.setField(manager, "modelName", MAIN_MODEL);
        ReflectionTestUtils.setField(manager, "fieldName", "manager");
        ReflectionTestUtils.setField(manager, "fieldType", FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(manager, "columnName", "manager_id");
        ReflectionTestUtils.setField(manager, "relatedModel", RELATED_MODEL);
        ReflectionTestUtils.setField(manager, "relatedField", "id");
        mm.when(() -> ModelManager.existField(MAIN_MODEL, "manager")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(MAIN_MODEL, "manager")).thenReturn(manager);
        mm.when(() -> ModelManager.getModelFieldColumn(RELATED_MODEL, "id")).thenReturn("id");

        // Stored field on related model: name → full_name column
        MetaField name = new MetaField();
        ReflectionTestUtils.setField(name, "modelName", RELATED_MODEL);
        ReflectionTestUtils.setField(name, "fieldName", "name");
        ReflectionTestUtils.setField(name, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(name, "columnName", "full_name");
        mm.when(() -> ModelManager.existField(RELATED_MODEL, "name")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(RELATED_MODEL, "name")).thenReturn(name);
    }

    private static void stubStoredField(MockedStatic<ModelManager> mm, String modelName,
                                        String fieldName, String columnName) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", modelName);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(field, "columnName", columnName);
        mm.when(() -> ModelManager.existField(modelName, fieldName)).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(modelName, fieldName)).thenReturn(field);
    }

    private static String orderByClause(SqlWrapper sqlWrapper) {
        Object clause = ReflectionTestUtils.getField(sqlWrapper, "orderByClause");
        return clause == null ? "" : clause.toString();
    }

    private static String joinClause(SqlWrapper sqlWrapper) {
        Object clause = ReflectionTestUtils.getField(sqlWrapper, "joinClause");
        return clause == null ? "" : clause.toString();
    }

    @SuppressWarnings("unused")
    private static Map<String, Set<String>> emptyAccessMap() {
        return new HashMap<>();
    }
}
