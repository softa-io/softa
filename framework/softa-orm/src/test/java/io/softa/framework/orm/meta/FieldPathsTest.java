package io.softa.framework.orm.meta;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * What a path may be used for, rule by rule, as the builders apply them — so a template's filters, orders
 * and columns can be checked when they are saved and answer what the run will.
 */
class FieldPathsTest {

    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        models = Mockito.mockStatic(ModelManager.class);
        models.when(() -> ModelManager.existModel(anyString())).thenAnswer(call ->
                List.of("Employee", "Department", "Project", "Company").contains(call.<String>getArgument(0)));
        models.when(() -> ModelManager.existField(anyString(), anyString())).thenReturn(false);
        stub("Employee", "name", FieldType.STRING, null);
        stub("Employee", "deptId", FieldType.MANY_TO_ONE, "Department");
        stub("Employee", "projectIds", FieldType.MANY_TO_MANY, "Project");
        // Worked out on read: no column of its own.
        stub("Employee", "age", FieldType.INTEGER, null).setDynamic(true);
        // A column of another model, which the builders rewrite to its path.
        MetaField companyName = stub("Employee", "companyName", FieldType.STRING, null);
        companyName.setDynamic(true);
        companyName.setCascadedField("deptId.companyId.name");
        stub("Department", "code", FieldType.STRING, null);
        stub("Department", "companyId", FieldType.MANY_TO_ONE, "Company");
        stub("Department", "employeeIds", FieldType.ONE_TO_MANY, "Employee");
        MetaField headcount = stub("Department", "headcount", FieldType.INTEGER, null);
        headcount.setDynamic(true);
        MetaField deptCompanyName = stub("Department", "companyName", FieldType.STRING, null);
        deptCompanyName.setDynamic(true);
        deptCompanyName.setCascadedField("companyId.name");
        stub("Project", "code", FieldType.STRING, null);
        MetaField projectCompany = stub("Project", "companyName", FieldType.STRING, null);
        projectCompany.setDynamic(true);
        projectCompany.setCascadedField("companyId.name");
        stub("Company", "name", FieldType.STRING, null);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private MetaField stub(String model, String fieldName, FieldType type, String relatedModel) {
        MetaField field = new MetaField();
        field.setModelName(model);
        field.setFieldName(fieldName);
        field.setFieldType(type);
        field.setRelatedModel(relatedModel);
        // A to-many relation has no column on this side.
        field.setDynamic(FieldType.TO_MANY_TYPES.contains(type));
        models.when(() -> ModelManager.existField(model, fieldName)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(model, fieldName)).thenReturn(field);
        return field;
    }

    @Test
    void everyUseAcceptsAPathThroughToOneRelationsToAColumn() {
        for (FieldPathUse use : FieldPathUse.values()) {
            assertNull(FieldPaths.problem("Employee", "deptId.companyId.name", use), use.name());
            assertNull(FieldPaths.problem("Employee", "name", use), use.name());
        }
    }

    @Test
    void onlyAConditionCrossesAToManyRelation() {
        assertNull(FieldPaths.problem("Employee", "projectIds.code", FieldPathUse.FILTER));
        assertEquals("Cannot cascade through 'projectIds' (ManyToMany).",
                FieldPaths.problem("Employee", "projectIds.code", FieldPathUse.SORT));
        assertNotNull(FieldPaths.problem("Employee", "projectIds.code", FieldPathUse.READ));
    }

    @Test
    void aConditionOnAToManyRelationComparesItsIds() {
        assertNull(FieldPaths.problem("Employee", "projectIds", FieldPathUse.FILTER));
        assertNull(FieldPaths.problem("Employee", "deptId.employeeIds", FieldPathUse.FILTER));
        assertEquals("Cannot sort by 'projectIds' (ManyToMany).",
                FieldPaths.problem("Employee", "projectIds", FieldPathUse.SORT));
    }

    @Test
    void aFieldWorkedOutOnReadCannotBeFilteredOrSortedButCanBeRead() {
        assertEquals("'age' is not stored, so a condition cannot test it.",
                FieldPaths.problem("Employee", "age", FieldPathUse.FILTER));
        assertEquals("'age' is not stored, so there is no column to sort by.",
                FieldPaths.problem("Employee", "age", FieldPathUse.SORT));
        assertEquals("'headcount' is not stored, so a condition cannot test it.",
                FieldPaths.problem("Employee", "deptId.headcount", FieldPathUse.FILTER));
        assertNull(FieldPaths.problem("Employee", "deptId.headcount", FieldPathUse.READ));
    }

    @Test
    void aCascadedFieldIsRewrittenOnlyWhereItsQueryStarts() {
        // The model's own: rewritten to its path.
        assertNull(FieldPaths.problem("Employee", "companyName", FieldPathUse.FILTER));
        assertNull(FieldPaths.problem("Employee", "companyName", FieldPathUse.SORT));
        // At the end of a join it is a column the related table does not have.
        assertEquals("'companyName' is not stored, so a condition cannot test it.",
                FieldPaths.problem("Employee", "deptId.companyName", FieldPathUse.FILTER));
        assertEquals("'companyName' is not stored, so there is no column to sort by.",
                FieldPaths.problem("Employee", "deptId.companyName", FieldPathUse.SORT));
        // Right after a to-many hop it starts the sub-query, and is rewritten there.
        assertNull(FieldPaths.problem("Employee", "projectIds.companyName", FieldPathUse.FILTER));
    }

    @Test
    void aMissingFieldOrModelIsNamed() {
        assertEquals("Field 'ghost' not found on model 'Department'.",
                FieldPaths.problem("Employee", "deptId.ghost", FieldPathUse.READ));
        assertEquals("Model 'Nope' does not exist.", FieldPaths.problem("Nope", "name", FieldPathUse.FILTER));
        assertEquals("Cannot cascade through 'name' (String).",
                FieldPaths.problem("Employee", "name.length", FieldPathUse.FILTER));
        assertTrue(FieldPaths.problem("Employee", "a.b.c.d.e.f", FieldPathUse.READ).contains("maximum cascade depth"));
    }

    @Test
    void theNameSearchIsAConditionOfItsOwn() {
        assertNull(FieldPaths.problem("Employee", "searchName", FieldPathUse.FILTER));
        assertNotNull(FieldPaths.problem("Employee", "searchName", FieldPathUse.SORT));
    }

    @Test
    void aConditionIsCheckedFieldByFieldThroughItsNesting() {
        Filters filters = Filters.of("[[\"name\", \"=\", \"A\"], \"OR\", [[\"age\", \">\", 3], \"AND\","
                + " [\"deptId.ghost\", \"=\", 1]]]");

        assertEquals(List.of("'age': 'age' is not stored, so a condition cannot test it.",
                "'deptId.ghost': Field 'ghost' not found on model 'Department'."),
                FieldPaths.filterProblems("Employee", filters));
        assertTrue(FieldPaths.filterProblems("Employee", null).isEmpty());
        assertTrue(FieldPaths.filterProblems("Employee", new Filters()).isEmpty());
    }

    @Test
    void aConditionOnSeveralFieldsJoinsToEveryOne() {
        // Compared column by column: no sub-query, so no to-many relation among them.
        Filters tuple = Filters.of(List.of("name", "projectIds"), io.softa.framework.base.enums.Operator.IN,
                List.of(List.of("A", 1)));

        assertEquals(List.of("'projectIds': A condition on several fields cannot compare 'projectIds' (ManyToMany)."),
                FieldPaths.filterProblems("Employee", tuple));
    }

    @Test
    void anOrderIsCheckedFieldByField() {
        Orders orders = Orders.of("name ASC, deptId.code DESC, age ASC");

        assertEquals(List.of("'age': 'age' is not stored, so there is no column to sort by."),
                FieldPaths.orderProblems("Employee", orders));
        assertTrue(FieldPaths.orderProblems("Employee", null).isEmpty());
    }
}
