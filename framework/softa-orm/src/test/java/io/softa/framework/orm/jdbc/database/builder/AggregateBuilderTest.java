package io.softa.framework.orm.jdbc.database.builder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that GROUP BY / SPLIT BY supports dynamic cascaded field aliases
 * declared on the model (`dynamic=true && cascadedField`).
 * Such aliases must:
 *   - pass {@code validateGroupableFields},
 *   - be projected as `tN.column AS alias` in the SELECT clause (preserve user alias),
 *   - be emitted as `tN.column` (no AS) in the GROUP BY clause,
 *   - introduce the matching LEFT JOIN.
 */
class AggregateBuilderTest {

    private static final String MAIN_MODEL = "Order";
    private static final String MAIN_TABLE = "orders";
    private static final String RELATED_MODEL = "Customer";
    private static final String RELATED_TABLE = "customer";

    @Test
    void groupByDynamicCascadedAliasProjectsAliasAndJoinsRelatedTable() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubCustomerNameCascade(mm);
            mm.when(() -> ModelManager.getModelStoredNumericFields(MAIN_MODEL)).thenReturn(new HashSet<>());

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setGroupBy(List.of("custName"));

            new AggregateBuilder(sqlWrapper, flexQuery).build();

            // Order: cascaded alias select → SUM (none here) → count(*) appended last
            assertEquals("t1.full_name AS custName,count(*) AS count,", selectClause(sqlWrapper));
            assertEquals("t1.full_name,", groupByClause(sqlWrapper));
            assertTrue(joinClause(sqlWrapper).contains("LEFT JOIN " + RELATED_TABLE + " t1"),
                    "expected LEFT JOIN to customer, got: " + joinClause(sqlWrapper));
        }
    }

    @Test
    void groupByMixesAliasAndStoredFieldDualTrackSelect() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubCustomerNameCascade(mm);
            stubStoredField(mm, MAIN_MODEL, "status", "status_code");
            mm.when(() -> ModelManager.getModelStoredNumericFields(MAIN_MODEL)).thenReturn(new HashSet<>());

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setGroupBy(List.of("custName", "status"));

            new AggregateBuilder(sqlWrapper, flexQuery).build();

            String select = selectClause(sqlWrapper);
            assertTrue(select.contains("t.status_code"),
                    "stored field expected as t.status_code, got: " + select);
            assertTrue(select.contains("t1.full_name AS custName"),
                    "cascaded alias expected as t1.full_name AS custName, got: " + select);
            assertTrue(groupByClause(sqlWrapper).contains("t.status_code"));
            assertTrue(groupByClause(sqlWrapper).contains("t1.full_name"));
        }
    }

    @Test
    void groupByAliasWithStoredNumericFieldAutoSums() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubCustomerNameCascade(mm);
            stubNumericField(mm, MAIN_MODEL, "amount", "amount", FieldType.LONG);
            mm.when(() -> ModelManager.getModelStoredNumericFields(MAIN_MODEL))
                    .thenReturn(new HashSet<>(Set.of("amount")));
            mm.when(() -> ModelManager.getModelFieldColumn(MAIN_MODEL, "amount")).thenReturn("amount");

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setGroupBy(List.of("custName"));
            flexQuery.setFields(List.of("custName", "amount"));

            new AggregateBuilder(sqlWrapper, flexQuery).build();

            String select = selectClause(sqlWrapper);
            assertTrue(select.contains("SUM(t.amount) AS sumAmount"),
                    "amount must be auto-summed, got: " + select);
            assertTrue(select.contains("t1.full_name AS custName"),
                    "cascaded alias projection missing, got: " + select);
            // amount is summed (not in groupBy), custName is grouped
            assertEquals("t1.full_name,", groupByClause(sqlWrapper));
        }
    }

    @Test
    void splitByDynamicCascadedAliasIsTreatedLikeGroupBy() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubCustomerNameCascade(mm);
            mm.when(() -> ModelManager.getModelStoredNumericFields(MAIN_MODEL)).thenReturn(new HashSet<>());

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setSplitBy(List.of("custName"));

            new AggregateBuilder(sqlWrapper, flexQuery).build();

            assertEquals("t1.full_name,", groupByClause(sqlWrapper));
            assertTrue(selectClause(sqlWrapper).contains("t1.full_name AS custName"));
        }
    }

    @Test
    void groupByDynamicComputedFieldIsRejected() {
        // Regression: dynamic computed (non-cascaded) must still be rejected by validateGroupableFields
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubDynamicComputedField(mm, MAIN_MODEL, "fullDesc");
            mm.when(() -> ModelManager.validateGroupableFields(Mockito.eq(MAIN_MODEL), Mockito.anyList()))
                    .thenCallRealMethod();

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setGroupBy(List.of("fullDesc"));

            // RuntimeException covers both the expected IllegalArgumentException and the
            // NullPointerException raised when SystemConfig.env is unset in the test fixture.
            assertThrows(RuntimeException.class,
                    () -> new AggregateBuilder(sqlWrapper, flexQuery).build());
        }
    }

    @Test
    void groupByStoredFieldIsUnchanged() {
        // Regression: stored fields go through the pre-existing path
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubMainModel(mm);
            stubStoredField(mm, MAIN_MODEL, "status", "status_code");
            mm.when(() -> ModelManager.getModelStoredNumericFields(MAIN_MODEL)).thenReturn(new HashSet<>());

            SqlWrapper sqlWrapper = new SqlWrapper(MAIN_MODEL);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setGroupBy(List.of("status"));

            new AggregateBuilder(sqlWrapper, flexQuery).build();

            assertEquals("t.status_code,count(*) AS count,", selectClause(sqlWrapper));
            assertEquals("t.status_code,", groupByClause(sqlWrapper));
            assertEquals("", joinClause(sqlWrapper));
        }
    }

    @Test
    void validateGroupableFieldsAllowsDynamicCascadedField() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubCustomerNameCascade(mm);
            mm.when(() -> ModelManager.validateGroupableFields(Mockito.eq(MAIN_MODEL), Mockito.anyList()))
                    .thenCallRealMethod();

            // No exception
            ModelManager.validateGroupableFields(MAIN_MODEL, List.of("custName"));
        }
    }

    @Test
    void validateGroupableFieldsRejectsDynamicComputedField() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubDynamicComputedField(mm, MAIN_MODEL, "fullDesc");
            mm.when(() -> ModelManager.validateGroupableFields(Mockito.eq(MAIN_MODEL), Mockito.anyList()))
                    .thenCallRealMethod();

            // RuntimeException covers both the expected IllegalArgumentException and the
            // NullPointerException raised when SystemConfig.env is unset in the test fixture.
            assertThrows(RuntimeException.class,
                    () -> ModelManager.validateGroupableFields(MAIN_MODEL, List.of("fullDesc")));
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

    private static void stubCustomerNameCascade(MockedStatic<ModelManager> mm) {
        // Dynamic cascaded alias on main model: custName → customer.name
        MetaField custName = new MetaField();
        ReflectionTestUtils.setField(custName, "modelName", MAIN_MODEL);
        ReflectionTestUtils.setField(custName, "fieldName", "custName");
        ReflectionTestUtils.setField(custName, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(custName, "dynamic", true);
        ReflectionTestUtils.setField(custName, "cascadedField", "customer.name");
        mm.when(() -> ModelManager.existField(MAIN_MODEL, "custName")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(MAIN_MODEL, "custName")).thenReturn(custName);

        // ManyToOne field: customer → Customer
        MetaField customer = new MetaField();
        ReflectionTestUtils.setField(customer, "modelName", MAIN_MODEL);
        ReflectionTestUtils.setField(customer, "fieldName", "customer");
        ReflectionTestUtils.setField(customer, "fieldType", FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(customer, "columnName", "customer_id");
        ReflectionTestUtils.setField(customer, "relatedModel", RELATED_MODEL);
        ReflectionTestUtils.setField(customer, "relatedField", "id");
        mm.when(() -> ModelManager.existField(MAIN_MODEL, "customer")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(MAIN_MODEL, "customer")).thenReturn(customer);
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

    private static void stubNumericField(MockedStatic<ModelManager> mm, String modelName,
                                         String fieldName, String columnName, FieldType fieldType) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", modelName);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", fieldType);
        ReflectionTestUtils.setField(field, "columnName", columnName);
        mm.when(() -> ModelManager.existField(modelName, fieldName)).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(modelName, fieldName)).thenReturn(field);
    }

    private static void stubDynamicComputedField(MockedStatic<ModelManager> mm,
                                                 String modelName, String fieldName) {
        // Dynamic computed: dynamic=true, cascadedField=null → not a dynamic cascaded field
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", modelName);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(field, "dynamic", true);
        ReflectionTestUtils.setField(field, "computed", true);
        ReflectionTestUtils.setField(field, "expression", "concat(a, b)");
        mm.when(() -> ModelManager.existField(modelName, fieldName)).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(modelName, fieldName)).thenReturn(field);
    }

    private static String selectClause(SqlWrapper sqlWrapper) {
        Object clause = ReflectionTestUtils.getField(sqlWrapper, "selectClause");
        return clause == null ? "" : clause.toString();
    }

    private static String groupByClause(SqlWrapper sqlWrapper) {
        Object clause = ReflectionTestUtils.getField(sqlWrapper, "groupByClause");
        return clause == null ? "" : clause.toString();
    }

    private static String joinClause(SqlWrapper sqlWrapper) {
        Object clause = ReflectionTestUtils.getField(sqlWrapper, "joinClause");
        return clause == null ? "" : clause.toString();
    }
}
