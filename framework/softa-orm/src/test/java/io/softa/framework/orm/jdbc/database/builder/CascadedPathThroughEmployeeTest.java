package io.softa.framework.orm.jdbc.database.builder;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.jdbc.database.dialect.PostgreSQLDialect;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The department grant reaches a record of an employee's through {@code employeeId.departmentId.idPath}: two
 * joins, and the two branches of a subtree match on the last table.
 */
class CascadedPathThroughEmployeeTest {

    private static final String PATH = "employeeId.departmentId.idPath";

    private MockedStatic<ModelManager> mm;
    private Object previousDialect;

    @BeforeEach
    void setUp() {
        previousDialect = ReflectionTestUtils.getField(DBUtil.class, "singleDbDialect");
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", new PostgreSQLDialect());
        mm = Mockito.mockStatic(ModelManager.class);
        mm.when(() -> ModelManager.isTimelineModel(Mockito.anyString())).thenReturn(false);
        table("EmpFamily", "emp_family");
        table("Employee", "employee");
        table("Department", "department");
        relation("EmpFamily", "employeeId", "employee_id", "Employee");
        relation("Employee", "departmentId", "department_id", "Department");
        MetaField idPath = field("Department", "idPath", "id_path", FieldType.STRING);
        mm.when(() -> ModelManager.getModelField("Department", "idPath")).thenReturn(idPath);
        mm.when(() -> ModelManager.getModelFieldColumn(Mockito.anyString(), Mockito.eq("id"))).thenReturn("id");
    }

    @AfterEach
    void tearDown() {
        mm.close();
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", previousDialect);
    }

    @Test
    void aSubtreeMatchJoinsThroughTheEmployeeToTheDepartment() {
        SqlWrapper sql = new SqlWrapper("EmpFamily");
        Filters subtree = Filters.or(Filters.of(PATH, Operator.EQUAL, "1/10"), new Filters().childOf(PATH, "1/10/"));

        new WhereBuilder(sql, new FlexQuery(subtree)).build();

        String joins = String.valueOf(ReflectionTestUtils.getField(sql, "joinClause"));
        assertThat(joins).contains("LEFT JOIN employee t1").contains("t.employee_id = t1.id")
                .contains("LEFT JOIN department t2").contains("t1.department_id = t2.id");
        String where = String.valueOf(ReflectionTestUtils.getField(sql, "whereClause"));
        assertThat(where).contains("t2.id_path =").contains("t2.id_path LIKE").contains(" OR ");
        Object sqlParams = ReflectionTestUtils.getField(sql, "sqlParams");
        @SuppressWarnings("unchecked")
        List<Object> args = new ArrayList<>((List<Object>) ReflectionTestUtils.getField(sqlParams, "args"));
        assertThat(args).containsExactly("1/10", "1/10/%");
    }

    private void table(String model, String table) {
        MetaModel meta = new MetaModel();
        ReflectionTestUtils.setField(meta, "modelName", model);
        ReflectionTestUtils.setField(meta, "tableName", table);
        mm.when(() -> ModelManager.getModel(model)).thenReturn(meta);
    }

    private void relation(String model, String fieldName, String column, String related) {
        MetaField field = field(model, fieldName, column, FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(field, "relatedModel", related);
        ReflectionTestUtils.setField(field, "relatedField", "id");
        mm.when(() -> ModelManager.getModelField(model, fieldName)).thenReturn(field);
    }

    private MetaField field(String model, String fieldName, String column, FieldType type) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", model);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "columnName", column);
        ReflectionTestUtils.setField(field, "fieldType", type);
        mm.when(() -> ModelManager.existField(model, fieldName)).thenReturn(true);
        return field;
    }
}
