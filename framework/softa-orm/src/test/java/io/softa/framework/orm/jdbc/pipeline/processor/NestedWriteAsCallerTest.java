package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.aspect.PermissionAspect;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The child rows a nested payload writes are the caller's own writes to another model. They run inside
 * the JDBC write, which skips permission checks for its own bookkeeping, and must come back out of it:
 * judged as the caller — the child model's field guards, sensitive fields and row range — and not under
 * the skip the parent's write runs in.
 */
class NestedWriteAsCallerTest {

    private MockedStatic<ReflectTool> reflect;
    private MockedStatic<ModelManager> models;
    /** Whether permission checks were skipped at each nested write, in call order. */
    private final List<Boolean> skippedAtWrite = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reflect = Mockito.mockStatic(ReflectTool.class);
        reflect.when(() -> ReflectTool.createList(anyString(), anyList())).thenAnswer(inv -> {
            skippedAtWrite.add(ContextHolder.getContext().isSkipPermissionCheck());
            List<?> rows = inv.getArgument(1);
            List<Serializable> ids = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) ids.add(100L + i);
            return ids;
        });
        reflect.when(() -> ReflectTool.updateList(anyString(), anyList())).thenAnswer(inv -> {
            skippedAtWrite.add(ContextHolder.getContext().isSkipPermissionCheck());
            return true;
        });
        reflect.when(() -> ReflectTool.deleteList(anyString(), anyList())).thenAnswer(inv -> {
            skippedAtWrite.add(ContextHolder.getContext().isSkipPermissionCheck());
            return true;
        });

        models = Mockito.mockStatic(ModelManager.class);
        MetaField id = new MetaField();
        ReflectionTestUtils.setField(id, "fieldName", "id");
        ReflectionTestUtils.setField(id, "fieldType", FieldType.LONG);
        models.when(() -> ModelManager.getModelField(anyString(), Mockito.eq("id"))).thenReturn(id);
        models.when(() -> ModelManager.getModelDisplayName(anyString())).thenReturn(List.of("name"));
    }

    @AfterEach
    void tearDown() {
        reflect.close();
        models.close();
    }

    private static MetaField field(String name, FieldType type, String relatedModel, String relatedField) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", "Employee");
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(field, "relatedField", relatedField);
        return field;
    }

    /** Run {@code write} the way a JDBC write method runs: inside @SkipPermissionCheck, for a caller. */
    private void insideTheJdbcWrite(boolean callerSkipped, Runnable write) {
        Context context = new Context();
        context.setUserId(1L);
        context.setSkipPermissionCheck(callerSkipped);
        ContextHolder.runWith(context, () -> {
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            try {
                when(joinPoint.proceed()).thenAnswer(inv -> {
                    assertThat(ContextHolder.getContext().isSkipPermissionCheck()).isTrue();
                    write.run();
                    return null;
                });
                new PermissionAspect().skipPermissionCheck(joinPoint);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            assertThat(ContextHolder.getContext().isSkipPermissionCheck()).isEqualTo(callerSkipped);
        });
    }

    private static List<Map<String, Object>> employeeWith(String field, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put(field, value);
        return new ArrayList<>(List.of(row));
    }

    private static Map<String, Object> child(Long id) {
        Map<String, Object> row = new HashMap<>();
        if (id != null) row.put("id", id);
        row.put("street", "S");
        return row;
    }

    @Test
    void oneToManyChildrenCreatedWithTheParentAreJudgedAsTheCaller() {
        OneToManyProcessor processor = new OneToManyProcessor(
                field("addresses", FieldType.ONE_TO_MANY, "EmpAddress", "employeeId"), AccessType.CREATE, null);

        insideTheJdbcWrite(false, () -> processor.batchProcessInputRows(
                employeeWith("addresses", new ArrayList<>(List.of(child(null))))));

        assertThat(skippedAtWrite).containsExactly(false);
    }

    @Test
    void oneToManyChildrenUpdatedWithTheParentAreJudgedAsTheCaller() {
        reflect.when(() -> ReflectTool.searchListIgnoringRowScope(anyString(), Mockito.any()))
                .thenReturn(List.of(new HashMap<>(Map.of("id", 10L, "employeeId", 1L)),
                        new HashMap<>(Map.of("id", 11L, "employeeId", 1L))));
        OneToManyProcessor processor = new OneToManyProcessor(
                field("addresses", FieldType.ONE_TO_MANY, "EmpAddress", "employeeId"), AccessType.UPDATE, null);

        // Keep 10, drop 11, add one: a create, an update and a delete.
        insideTheJdbcWrite(false, () -> processor.batchProcessInputRows(
                employeeWith("addresses", new ArrayList<>(List.of(child(10L), child(null))))));

        assertThat(skippedAtWrite).containsExactly(false, false, false);
    }

    @Test
    void aNestedOneToOneRowNamedByIdIsJudgedAsTheCaller() {
        XToOneGroupProcessor processor = new XToOneGroupProcessor(
                field("profileId", FieldType.ONE_TO_ONE, "EmployeeProfile", "id"), AccessType.UPDATE, null);

        insideTheJdbcWrite(false, () -> ReflectionTestUtils.invokeMethod(processor, "processNestedOneToOneRows",
                employeeWith("profileId", child(55L))));

        assertThat(skippedAtWrite).containsExactly(false, false);
    }

    @Test
    void aSystemWriteThatWasAlreadySkippingStaysSkipped() {
        OneToManyProcessor processor = new OneToManyProcessor(
                field("addresses", FieldType.ONE_TO_MANY, "EmpAddress", "employeeId"), AccessType.CREATE, null);

        insideTheJdbcWrite(true, () -> processor.batchProcessInputRows(
                employeeWith("addresses", new ArrayList<>(List.of(child(null))))));

        assertThat(skippedAtWrite).containsExactly(true);
    }
}
