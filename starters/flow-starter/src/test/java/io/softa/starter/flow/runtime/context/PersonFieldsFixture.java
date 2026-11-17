package io.softa.starter.flow.runtime.context;

import java.util.List;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A {@code TransferRequest} model held by a mocked {@link ModelManager}, with the fields an approver source may read
 * a person or a department from — onto an employee, a login account, a department — and those it may not: a relation
 * onto another model, a plain field, and a computed (dynamic) relation onto an employee.
 */
public final class PersonFieldsFixture {

    public static final String MODEL = "TransferRequest";

    /** Onto {@code Employee}: the employee the request is about, and their new manager. */
    public static final String EMPLOYEE = "employeeId";
    public static final String NEW_MANAGER = "newManagerId";
    /** Onto {@code UserAccount}. */
    public static final String REVIEWER_ACCOUNT = "reviewerAccountId";
    /** Onto {@code Department}. */
    public static final String NEW_DEPT = "newDeptId";
    /** Onto {@code Project}: neither a person nor a department. */
    public static final String PROJECT = "projectId";
    /** A plain text field. */
    public static final String REASON = "reason";
    /** Onto {@code Employee}, but computed: the request's approvals do not carry it. */
    public static final String COMPUTED_MANAGER = "currentManagerId";

    public static final List<MetaField> FIELDS = List.of(
            field("id", FieldType.LONG, null, "ID"),
            field(EMPLOYEE, FieldType.MANY_TO_ONE, "Employee", "Employee"),
            field(NEW_MANAGER, FieldType.MANY_TO_ONE, "Employee", "New Manager"),
            field(REVIEWER_ACCOUNT, FieldType.MANY_TO_ONE, "UserAccount", "Reviewer"),
            field(NEW_DEPT, FieldType.MANY_TO_ONE, "Department", "New Department"),
            field(PROJECT, FieldType.MANY_TO_ONE, "Project", "Project"),
            field(REASON, FieldType.STRING, null, "Reason"),
            dynamic(field(COMPUTED_MANAGER, FieldType.MANY_TO_ONE, "Employee", "Current Manager")));

    private PersonFieldsFixture() {
    }

    /** Opens the mock; close it after the test. */
    public static MockedStatic<ModelManager> mockModelManager() {
        MockedStatic<ModelManager> manager = mockStatic(ModelManager.class);
        manager.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
        manager.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(FIELDS);
        for (MetaField field : FIELDS) {
            manager.when(() -> ModelManager.existField(MODEL, field.getFieldName())).thenReturn(true);
            manager.when(() -> ModelManager.getModelField(MODEL, field.getFieldName())).thenReturn(field);
            if (field.getRelatedModel() != null) {
                manager.when(() -> ModelManager.existModel(field.getRelatedModel())).thenReturn(true);
            }
        }
        return manager;
    }

    private static MetaField field(String name, FieldType type, String relatedModel, String label) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(relatedModel);
        when(field.getLabel()).thenReturn(label);
        if (relatedModel != null) {
            when(field.getRelatedField()).thenReturn("id");
        }
        return field;
    }

    private static MetaField dynamic(MetaField field) {
        when(field.isDynamic()).thenReturn(true);
        return field;
    }
}
