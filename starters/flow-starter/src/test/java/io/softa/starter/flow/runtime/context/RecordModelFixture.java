package io.softa.starter.flow.runtime.context;

import java.util.List;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A {@code LeaveRequest} model held by a mocked {@link ModelManager}: fields its approvals route on, and
 * the kinds they leave out — its identity, bookkeeping and approval state, a to-many and a dynamic field,
 * and fields declared sensitive (encrypted, masked). Its {@code departmentId} points at a {@code Department},
 * whose fields a rule reads through it — and the kinds it may not — and whose {@code parentId} points at another,
 * a second hop on.
 */
public final class RecordModelFixture {

    public static final String MODEL = "LeaveRequest";

    public static final String RELATED = "Department";

    /** In the model's field order; the routed ones are days through reason. */
    public static final List<MetaField> FIELDS = List.of(
            field("id", FieldType.LONG),
            field("days", FieldType.BIG_DECIMAL),
            field("leaveType", FieldType.OPTION),
            field("startDate", FieldType.DATE),
            field("startTime", FieldType.DATE_TIME),
            relation("departmentId", RELATED),
            field("reason", FieldType.STRING),
            field("approvalStatus", FieldType.OPTION),
            field("createdTime", FieldType.DATE_TIME),
            field("attachments", FieldType.ONE_TO_MANY),
            dynamic("summary", FieldType.STRING),
            encrypted("bankAccount", FieldType.STRING),
            masked("phone", FieldType.STRING));

    public static final List<String> ROUTED = List.of("days", "leaveType", "startDate", "startTime", "departmentId",
            "reason");

    /** The record {@code departmentId} points at; the routed ones are costCenter through parentId. */
    public static final List<MetaField> RELATED_FIELDS = List.of(
            field("id", FieldType.LONG),
            field("costCenter", FieldType.STRING),
            field("level", FieldType.INTEGER),
            field("establishedOn", FieldType.DATE),
            relation("parentId", RELATED),
            field("updatedTime", FieldType.DATE_TIME),
            dynamic("headcount", FieldType.INTEGER),
            encrypted("budget", FieldType.BIG_DECIMAL));

    public static final List<String> RELATED_ROUTED = List.of("costCenter", "level", "establishedOn", "parentId");

    private RecordModelFixture() {
    }

    /** Opens the mock; close it after the test. */
    public static MockedStatic<ModelManager> mockModelManager() {
        MockedStatic<ModelManager> manager = mockStatic(ModelManager.class);
        register(manager, MODEL, FIELDS);
        register(manager, RELATED, RELATED_FIELDS);
        return manager;
    }

    private static void register(MockedStatic<ModelManager> manager, String model, List<MetaField> fields) {
        manager.when(() -> ModelManager.existModel(model)).thenReturn(true);
        manager.when(() -> ModelManager.getModelFields(model)).thenReturn(fields);
        for (MetaField field : fields) {
            manager.when(() -> ModelManager.existField(model, field.getFieldName())).thenReturn(true);
            manager.when(() -> ModelManager.getModelField(model, field.getFieldName())).thenReturn(field);
        }
    }

    private static MetaField field(String name, FieldType type) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        return field;
    }

    private static MetaField relation(String name, String relatedModel) {
        MetaField field = field(name, FieldType.MANY_TO_ONE);
        when(field.getRelatedModel()).thenReturn(relatedModel);
        when(field.getRelatedField()).thenReturn("id");
        return field;
    }

    private static MetaField dynamic(String name, FieldType type) {
        MetaField field = field(name, type);
        when(field.isDynamic()).thenReturn(true);
        return field;
    }

    private static MetaField encrypted(String name, FieldType type) {
        MetaField field = field(name, type);
        when(field.isEncrypted()).thenReturn(true);
        return field;
    }

    private static MetaField masked(String name, FieldType type) {
        MetaField field = field(name, type);
        when(field.getMaskingType()).thenReturn(MaskingType.values()[0]);
        return field;
    }
}
