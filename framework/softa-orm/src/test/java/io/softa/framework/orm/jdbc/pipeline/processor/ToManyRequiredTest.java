package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * A required one-to-many or many-to-many field holds at least one row (link), as a required column holds a value:
 * a new record names one in its value, and an update that sends the field leaves one — counted once the write is
 * done, since a patch that deletes every row is not an empty value. An update that leaves the
 * field out is not judged, and a field that is not required is not either.
 */
class ToManyRequiredTest {

    private MockedStatic<ReflectTool> reflect;
    private MockedStatic<ModelManager> models;
    /** The parents the count after the write finds rows for. */
    private final List<Long> parentsHoldingRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reflect = Mockito.mockStatic(ReflectTool.class);
        reflect.when(() -> ReflectTool.createList(anyString(), anyList())).thenAnswer(inv -> {
            List<?> rows = inv.getArgument(1);
            List<Serializable> ids = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) ids.add(100L + i);
            return ids;
        });
        reflect.when(() -> ReflectTool.updateList(anyString(), anyList())).thenReturn(true);
        reflect.when(() -> ReflectTool.deleteList(anyString(), anyList())).thenReturn(true);
        reflect.when(() -> ReflectTool.searchRelatedRows(anyString(), any()))
                .thenReturn(List.of(new HashMap<>(Map.of("id", 7L))));
        // The rows each parent held before the write: one line (10) / one link (20 → right 7).
        reflect.when(() -> ReflectTool.searchListIgnoringRowScope(anyString(), argThat(ToManyRequiredTest::previousRows)))
                .thenAnswer(inv -> "EmpRole".equals(inv.getArgument(0))
                        ? List.of(new HashMap<>(Map.of("id", 20L, "employeeId", 1L, "roleId", 7L)))
                        : List.of(new HashMap<>(Map.of("id", 10L, "employeeId", 1L))));
        // The count after the write: grouped by the parent.
        reflect.when(() -> ReflectTool.searchListIgnoringRowScope(anyString(), argThat(ToManyRequiredTest::countAfterWrite)))
                .thenAnswer(inv -> parentsHoldingRows.stream()
                        .map(id -> (Map<String, Object>) new HashMap<String, Object>(Map.of("employeeId", id, "count", 1)))
                        .toList());

        models = Mockito.mockStatic(ModelManager.class);
        MetaField longId = new MetaField();
        ReflectionTestUtils.setField(longId, "fieldName", "id");
        ReflectionTestUtils.setField(longId, "fieldType", FieldType.LONG);
        models.when(() -> ModelManager.getModelField(anyString(), anyString())).thenReturn(longId);
        models.when(() -> ModelManager.getModelDisplayName(anyString())).thenReturn(List.of("name"));
        models.when(() -> ModelManager.existModel(anyString())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        reflect.close();
        models.close();
    }

    private static boolean previousRows(FlexQuery query) {
        return query != null && query.getGroupBy().isEmpty();
    }

    private static boolean countAfterWrite(FlexQuery query) {
        return query != null && !query.getGroupBy().isEmpty();
    }

    private static MetaField lines(boolean required) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", "Employee");
        ReflectionTestUtils.setField(field, "fieldName", "lineIds");
        ReflectionTestUtils.setField(field, "label", "Lines");
        ReflectionTestUtils.setField(field, "fieldType", FieldType.ONE_TO_MANY);
        ReflectionTestUtils.setField(field, "relatedModel", "EmpLine");
        ReflectionTestUtils.setField(field, "relatedField", "employeeId");
        ReflectionTestUtils.setField(field, "required", required);
        return field;
    }

    private static MetaField roles(boolean required) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", "Employee");
        ReflectionTestUtils.setField(field, "fieldName", "roleIds");
        ReflectionTestUtils.setField(field, "label", "Roles");
        ReflectionTestUtils.setField(field, "fieldType", FieldType.MANY_TO_MANY);
        ReflectionTestUtils.setField(field, "relatedModel", "Role");
        ReflectionTestUtils.setField(field, "joinModel", "EmpRole");
        ReflectionTestUtils.setField(field, "joinLeft", "employeeId");
        ReflectionTestUtils.setField(field, "joinRight", "roleId");
        ReflectionTestUtils.setField(field, "required", required);
        return field;
    }

    private static List<Map<String, Object>> employeeWith(String field, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put(field, value);
        return new ArrayList<>(List.of(row));
    }

    private static List<Map<String, Object>> employeeWithout() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("name", "A");
        return new ArrayList<>(List.of(row));
    }

    private static Map<String, Object> line(Long id) {
        Map<String, Object> row = new HashMap<>();
        if (id != null) row.put("id", id);
        row.put("days", 2);
        return row;
    }

    @Test
    void aNewRecordMustNameARowOfARequiredTable() {
        OneToManyProcessor processor = new OneToManyProcessor(lines(true), AccessType.CREATE, null);

        assertThatThrownBy(() -> processor.batchProcessInputRows(employeeWith("lineIds", List.of())))
                .isInstanceOf(WriteValidationException.class)
                .hasMessageContaining("Lines is required");
        assertThatThrownBy(() -> processor.batchProcessInputRows(employeeWithout()))
                .isInstanceOf(WriteValidationException.class);
        assertThatThrownBy(() -> processor.batchProcessInputRows(employeeWith("lineIds", Map.of("Create", List.of()))))
                .isInstanceOf(WriteValidationException.class);
        reflect.verify(() -> ReflectTool.createList(anyString(), anyList()), Mockito.never());

        assertThatCode(() -> processor.batchProcessInputRows(employeeWith("lineIds", List.of(line(null)))))
                .doesNotThrowAnyException();
        assertThatCode(() -> processor.batchProcessInputRows(
                employeeWith("lineIds", Map.of("Create", List.of(line(null)))))).doesNotThrowAnyException();
    }

    @Test
    void deletingEveryRowOfARequiredTableIsRefusedOnTheRowsTheWriteLeaves() {
        OneToManyProcessor processor = new OneToManyProcessor(lines(true), AccessType.UPDATE, null);

        assertThatThrownBy(() -> processor.batchProcessInputRows(
                employeeWith("lineIds", Map.of("Delete", List.of(10L)))))
                .isInstanceOf(WriteValidationException.class)
                .hasMessageContaining("Lines is required");
        reflect.verify(() -> ReflectTool.deleteList(eq("EmpLine"), eq(List.of(10L))));
    }

    @Test
    void anUpdateThatLeavesARowPasses() {
        parentsHoldingRows.add(1L);
        OneToManyProcessor processor = new OneToManyProcessor(lines(true), AccessType.UPDATE, null);

        assertThatCode(() -> processor.batchProcessInputRows(employeeWith("lineIds",
                Map.of("Delete", List.of(10L), "Create", List.of(line(null)))))).doesNotThrowAnyException();
    }

    @Test
    void anUpdateThatLeavesTheTableOutIsNotJudged() {
        OneToManyProcessor processor = new OneToManyProcessor(lines(true), AccessType.UPDATE, null);

        assertThatCode(() -> processor.batchProcessInputRows(employeeWithout())).doesNotThrowAnyException();
        reflect.verify(() -> ReflectTool.searchListIgnoringRowScope(anyString(),
                argThat(ToManyRequiredTest::countAfterWrite)), Mockito.never());
    }

    @Test
    void aTableThatIsNotRequiredMayBeEmptied() {
        OneToManyProcessor processor = new OneToManyProcessor(lines(false), AccessType.UPDATE, null);

        assertThatCode(() -> processor.batchProcessInputRows(employeeWith("lineIds", List.of())))
                .doesNotThrowAnyException();
        assertThatCode(() -> new OneToManyProcessor(lines(false), AccessType.CREATE, null)
                .batchProcessInputRows(employeeWith("lineIds", List.of()))).doesNotThrowAnyException();
        reflect.verify(() -> ReflectTool.searchListIgnoringRowScope(anyString(),
                argThat(ToManyRequiredTest::countAfterWrite)), Mockito.never());
    }

    @Test
    void aRequiredManyToManyKeepsALink() {
        assertThatThrownBy(() -> new ManyToManyProcessor(roles(true), AccessType.CREATE, null)
                .batchProcessInputRows(employeeWith("roleIds", List.of())))
                .isInstanceOf(WriteValidationException.class)
                .hasMessageContaining("Roles is required");
        assertThatCode(() -> new ManyToManyProcessor(roles(true), AccessType.CREATE, null)
                .batchProcessInputRows(employeeWith("roleIds", List.of(7L)))).doesNotThrowAnyException();

        // Removing the only link leaves none.
        assertThatThrownBy(() -> new ManyToManyProcessor(roles(true), AccessType.UPDATE, null)
                .batchProcessInputRows(employeeWith("roleIds", Map.of("Remove", List.of(7L)))))
                .isInstanceOf(WriteValidationException.class);
        reflect.verify(() -> ReflectTool.deleteList(eq("EmpRole"), eq(List.of(20L))));
    }
}
