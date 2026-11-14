package io.softa.framework.orm.jdbc.database.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.jdbc.database.dialect.PostgreSQLDialect;
import io.softa.framework.orm.meta.FieldPathUse;
import io.softa.framework.orm.meta.FieldPaths;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * {@link FieldPaths} — what an export template's filters and orders are checked with when it is saved — is written to
 * the rules the query builders apply, but the builders walk a path on their own. This holds them to one answer: for
 * every path below, FieldPaths finds it usable in a condition exactly when the WHERE builder builds a condition on it,
 * and in an order exactly when the ORDER BY builder orders by it. Should a builder start taking or refusing a path
 * the other way, a template would save and then fail when it runs, or be refused for a query that would have run —
 * and this fails first.
 *
 * <p>A builder takes a path when it builds without an exception and every column it names exists. A field worked out
 * on read has no column: here its column is a marker no query may name, since the builders do not refuse such a field
 * themselves — the database does, when the query runs.
 */
class FieldPathsMatchBuildersTest {

    /** The column of a field that has none: a query naming it would fail on the database. */
    private static final String NO_COLUMN = "no_column_";

    private static final String EMPLOYEE = "Employee";
    private static final String DEPARTMENT = "Department";
    private static final String PROFILE = "Profile";
    private static final String ROLE = "Role";

    /** Paths on Employee, of every shape the rules tell apart. */
    private static final List<String> PATHS = List.of(
            // the model's own fields
            "name", "age", "deptName", "deptId", "roleIds", "ghost",
            // one level through a to-one relation
            "deptId.name", "profileId.passportNo", "deptId.headcount", "deptId.parentName", "deptId.ghost",
            // further, up to the depth limit and past it
            "deptId.parentId.name",
            "deptId.parentId.parentId.parentId.name",
            "deptId.parentId.parentId.parentId.parentId.name",
            // through a to-many relation, at the start and in the middle
            "roleIds.code", "deptId.employeeIds.name",
            // through a field that is no relation
            "name.length");

    private MockedStatic<ModelManager> models;
    private MockedStatic<ReflectTool> reflectTool;
    private Object previousDialect;

    @BeforeEach
    void setUp() {
        previousDialect = ReflectionTestUtils.getField(DBUtil.class, "singleDbDialect");
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", new PostgreSQLDialect());
        models = Mockito.mockStatic(ModelManager.class);
        models.when(() -> ModelManager.isTimelineModel(anyString())).thenReturn(false);
        models.when(() -> ModelManager.isMultiTenantControl(anyString())).thenReturn(false);
        models.when(() -> ModelManager.isSoftDeleted(anyString())).thenReturn(false);
        models.when(() -> ModelManager.isActiveControl(anyString())).thenReturn(false);
        models.when(() -> ModelManager.getModelPrimaryKey(anyString())).thenReturn(ModelConstant.ID);
        models.when(() -> ModelManager.getModelFieldColumn(anyString(), Mockito.eq(ModelConstant.ID)))
                .thenReturn(ModelConstant.ID);
        model(EMPLOYEE, "employee");
        model(DEPARTMENT, "department");
        model(PROFILE, "profile");
        model(ROLE, "role");

        stored(EMPLOYEE, ModelConstant.ID, FieldType.LONG);
        stored(EMPLOYEE, "name", FieldType.STRING);
        computed(EMPLOYEE, "age", FieldType.INTEGER);
        cascaded(EMPLOYEE, "deptName", "deptId.name");
        relation(EMPLOYEE, "deptId", FieldType.MANY_TO_ONE, DEPARTMENT);
        relation(EMPLOYEE, "profileId", FieldType.ONE_TO_ONE, PROFILE);
        MetaField roles = toMany(EMPLOYEE, "roleIds", FieldType.MANY_TO_MANY, ROLE);
        ReflectionTestUtils.setField(roles, "joinModel", "EmployeeRole");
        ReflectionTestUtils.setField(roles, "joinLeft", "employeeId");
        ReflectionTestUtils.setField(roles, "joinRight", "roleId");

        stored(DEPARTMENT, ModelConstant.ID, FieldType.LONG);
        stored(DEPARTMENT, "name", FieldType.STRING);
        computed(DEPARTMENT, "headcount", FieldType.INTEGER);
        cascaded(DEPARTMENT, "parentName", "parentId.name");
        relation(DEPARTMENT, "parentId", FieldType.MANY_TO_ONE, DEPARTMENT);
        MetaField employees = toMany(DEPARTMENT, "employeeIds", FieldType.ONE_TO_MANY, EMPLOYEE);
        ReflectionTestUtils.setField(employees, "relatedField", "deptId");

        stored(PROFILE, ModelConstant.ID, FieldType.LONG);
        stored(PROFILE, "passportNo", FieldType.STRING);
        stored(ROLE, ModelConstant.ID, FieldType.LONG);
        stored(ROLE, "code", FieldType.STRING);

        // A condition through a to-many relation reads the matching ids first; any will do here.
        reflectTool = Mockito.mockStatic(ReflectTool.class);
        reflectTool.when(() -> ReflectTool.getIds(anyString(), any(Filters.class))).thenReturn(List.of(5L));
        reflectTool.when(() -> ReflectTool.getRelatedIds(anyString(), anyString(), any(Filters.class)))
                .thenReturn(List.of(3L));
    }

    @AfterEach
    void tearDown() {
        reflectTool.close();
        models.close();
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", previousDialect);
    }

    @Test
    void aPathIsUsableInAConditionExactlyWhenTheWhereBuilderBuildsOne() {
        assertSameAnswers(FieldPathUse.FILTER, this::whereBuilds);
    }

    @Test
    void aPathIsUsableInAnOrderExactlyWhenTheOrderByBuilderOrdersByIt() {
        assertSameAnswers(FieldPathUse.SORT, this::orderBuilds);
    }

    /** Every path answered alike, and the list holds both answers — a list all one way would prove nothing. */
    private void assertSameAnswers(FieldPathUse use, Predicate<String> builds) {
        List<String> differences = new ArrayList<>();
        int usable = 0;
        for (String path : PATHS) {
            String problem = FieldPaths.problem(EMPLOYEE, path, use);
            boolean built = builds.test(path);
            if (problem == null) {
                usable++;
            }
            if ((problem == null) != built) {
                differences.add(path + ": FieldPaths " + (problem == null ? "takes it" : "refuses it (" + problem + ")")
                        + ", the builder " + (built ? "builds it" : "does not"));
            }
        }
        assertEquals(List.of(), differences);
        assertTrue(usable > 0 && usable < PATHS.size(), "the paths must include usable and unusable ones");
    }

    private boolean whereBuilds(String path) {
        try {
            SqlWrapper sqlWrapper = new SqlWrapper(EMPLOYEE);
            new WhereBuilder(sqlWrapper, new FlexQuery(Filters.of(path, Operator.EQUAL, 1))).build();
            return namesOnlyColumns(sqlWrapper, "whereClause");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean orderBuilds(String path) {
        try {
            SqlWrapper sqlWrapper = new SqlWrapper(EMPLOYEE);
            FlexQuery flexQuery = new FlexQuery();
            flexQuery.setOrders(Orders.ofAsc(path));
            new OrderByBuilder(sqlWrapper, flexQuery).build();
            return namesOnlyColumns(sqlWrapper, "orderByClause");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The clause was built, and neither it nor its joins name a column that does not exist. */
    private static boolean namesOnlyColumns(SqlWrapper sqlWrapper, String clauseField) {
        String clause = text(ReflectionTestUtils.getField(sqlWrapper, clauseField));
        String joins = text(ReflectionTestUtils.getField(sqlWrapper, "joinClause"));
        return !clause.isBlank() && !(clause + joins).contains(NO_COLUMN);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private void model(String modelName, String tableName) {
        MetaModel model = new MetaModel();
        ReflectionTestUtils.setField(model, "modelName", modelName);
        ReflectionTestUtils.setField(model, "tableName", tableName);
        models.when(() -> ModelManager.getModel(modelName)).thenReturn(model);
        models.when(() -> ModelManager.existModel(modelName)).thenReturn(true);
    }

    private MetaField field(String modelName, String fieldName, FieldType type, String columnName) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", modelName);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "label", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "columnName", columnName);
        models.when(() -> ModelManager.existField(modelName, fieldName)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(modelName, fieldName)).thenReturn(field);
        return field;
    }

    private void stored(String modelName, String fieldName, FieldType type) {
        field(modelName, fieldName, type, snake(fieldName));
    }

    /** Worked out on read, from the row itself: no column. */
    private void computed(String modelName, String fieldName, FieldType type) {
        ReflectionTestUtils.setField(field(modelName, fieldName, type, NO_COLUMN + snake(fieldName)), "dynamic", true);
    }

    /** Read through a relation on read: no column of its own. */
    private void cascaded(String modelName, String fieldName, String cascadedField) {
        MetaField field = field(modelName, fieldName, FieldType.STRING, NO_COLUMN + snake(fieldName));
        ReflectionTestUtils.setField(field, "dynamic", true);
        ReflectionTestUtils.setField(field, "cascadedField", cascadedField);
    }

    private void relation(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField field = field(modelName, fieldName, type, snake(fieldName));
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(field, "relatedField", ModelConstant.ID);
    }

    /** A collection of other rows: no column, as the metadata marks every to-many field dynamic. */
    private MetaField toMany(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField field = field(modelName, fieldName, type, NO_COLUMN + snake(fieldName));
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(field, "dynamic", true);
        return field;
    }

    private static String snake(String fieldName) {
        return fieldName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
