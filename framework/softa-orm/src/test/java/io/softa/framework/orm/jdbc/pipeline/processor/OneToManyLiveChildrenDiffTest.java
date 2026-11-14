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

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * An update diffs a one-to-many against the children its parent still holds. A soft-deleted child is no longer
 * read under the parent, so a list sent back leaves it out; counted as existing, it would be deleted again on
 * every save, and a caller without delete rights on the children would be refused a save that deletes nothing.
 */
class OneToManyLiveChildrenDiffTest {

    private static final String RELATED = "ClaimLine";

    private MockedStatic<ReflectTool> reflect;
    private MockedStatic<ModelManager> models;
    private final List<List<?>> deleted = new ArrayList<>();
    private final List<List<?>> updated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reflect = Mockito.mockStatic(ReflectTool.class);
        reflect.when(() -> ReflectTool.createList(anyString(), anyList())).thenAnswer(inv -> {
            List<?> rows = inv.getArgument(1);
            List<Serializable> ids = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) ids.add(100L + i);
            return ids;
        });
        reflect.when(() -> ReflectTool.updateList(anyString(), anyList())).thenAnswer(inv -> {
            List<?> rows = inv.getArgument(1);
            if (!rows.isEmpty()) updated.add(rows);
            return !rows.isEmpty();
        });
        reflect.when(() -> ReflectTool.deleteList(anyString(), anyList())).thenAnswer(inv -> {
            List<?> ids = inv.getArgument(1);
            if (!ids.isEmpty()) deleted.add(ids);
            return !ids.isEmpty();
        });
        // Line 10 is live, line 11 was soft-deleted.
        reflect.when(() -> ReflectTool.searchListIgnoringRowScope(eq(RELATED), any()))
                .thenReturn(List.of(new HashMap<>(Map.of("id", 10L, "claimId", 1L, "deleted", false)),
                        new HashMap<>(Map.of("id", 11L, "claimId", 1L, "deleted", true))));

        models = Mockito.mockStatic(ModelManager.class);
        MetaField id = new MetaField();
        ReflectionTestUtils.setField(id, "fieldName", "id");
        ReflectionTestUtils.setField(id, "fieldType", FieldType.LONG);
        models.when(() -> ModelManager.getModelField(anyString(), eq("id"))).thenReturn(id);
        models.when(() -> ModelManager.isSoftDeleted(RELATED)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        reflect.close();
        models.close();
    }

    private static OneToManyProcessor processor() {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", "Claim");
        ReflectionTestUtils.setField(field, "fieldName", "lines");
        ReflectionTestUtils.setField(field, "fieldType", FieldType.ONE_TO_MANY);
        ReflectionTestUtils.setField(field, "relatedModel", RELATED);
        ReflectionTestUtils.setField(field, "relatedField", "claimId");
        return new OneToManyProcessor(field, AccessType.UPDATE, null);
    }

    private static Map<String, Object> line(Long id) {
        Map<String, Object> row = new HashMap<>();
        if (id != null) row.put("id", id);
        row.put("amount", 5);
        return row;
    }

    private static void write(Object value) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("id", 1L);
        claim.put("lines", value);
        Context context = new Context();
        context.setUserId(1L);
        ContextHolder.runWith(context, () -> processor().batchProcessInputRows(new ArrayList<>(List.of(claim))));
    }

    @Test
    void aListSentBackWithoutTheDeletedChildDeletesNothing() {
        write(new ArrayList<>(List.of(line(10L))));

        assertThat(deleted).isEmpty();
        assertThat(updated).hasSize(1);
        reflect.verify(() -> ReflectTool.checkNestedWrite(RELATED, AccessType.DELETE, "Claim", "lines"), Mockito.never());
    }

    @Test
    void aListDroppingTheLiveChildDeletesOnlyIt() {
        write(new ArrayList<>(List.of(line(null))));

        assertThat(deleted).containsExactly(List.of(10L));
    }

    @Test
    void anEmptyListDeletesOnlyTheLiveChildren() {
        write(new ArrayList<>());

        assertThat(deleted).containsExactly(List.of(10L));
    }

    @Test
    void aDeletedChildSentBackInAListIsRefused() {
        assertThatThrownBy(() -> write(new ArrayList<>(List.of(line(10L), line(11L)))))
                .hasMessageContaining("has been deleted");
        assertThat(updated).isEmpty();
        assertThat(deleted).isEmpty();
    }

    @Test
    void aPatchUpdatingADeletedChildIsRefused() {
        assertThatThrownBy(() -> write(new HashMap<>(Map.of("Update", new ArrayList<>(List.of(line(11L)))))))
                .hasMessageContaining("has been deleted");
        assertThat(updated).isEmpty();
    }

    @Test
    void aPatchDeletingADeletedChildIsANoOp() {
        write(new HashMap<>(Map.of("Delete", new ArrayList<>(List.of(11L)))));

        assertThat(deleted).isEmpty();
    }

    @Test
    void aPatchDeletingBothDeletesOnlyTheLiveOne() {
        write(new HashMap<>(Map.of("Delete", new ArrayList<>(List.of(10L, 11L)))));

        assertThat(deleted).containsExactly(List.of(10L));
    }
}
