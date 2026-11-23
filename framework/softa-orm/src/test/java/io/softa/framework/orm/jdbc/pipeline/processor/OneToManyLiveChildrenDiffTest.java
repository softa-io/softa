package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
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
        Map<Long, Object> linesByClaim = new HashMap<>();
        linesByClaim.put(1L, value);
        writeAll(linesByClaim);
    }

    /** One write of several parents, each with the value of its lines. */
    private static void writeAll(Map<Long, Object> linesByClaim) {
        List<Map<String, Object>> claims = new ArrayList<>();
        linesByClaim.forEach((claimId, value) -> {
            Map<String, Object> claim = new HashMap<>();
            claim.put("id", claimId);
            claim.put("lines", value);
            claims.add(claim);
        });
        Context context = new Context();
        context.setUserId(1L);
        ContextHolder.runWith(context, () -> processor().batchProcessInputRows(claims));
    }

    /** Claim 1 holds live line 10 and deleted line 11; claim 2 holds live line 20 and deleted line 21. */
    private void twoClaims() {
        reflect.when(() -> ReflectTool.searchListIgnoringRowScope(eq(RELATED), any()))
                .thenReturn(List.of(new HashMap<>(Map.of("id", 10L, "claimId", 1L, "deleted", false)),
                        new HashMap<>(Map.of("id", 11L, "claimId", 1L, "deleted", true)),
                        new HashMap<>(Map.of("id", 20L, "claimId", 2L, "deleted", false)),
                        new HashMap<>(Map.of("id", 21L, "claimId", 2L, "deleted", true))));
    }

    @Test
    void severalParentsInOneWrite_eachDiffsAgainstItsOwnLiveChildren() {
        twoClaims();

        writeAll(Map.of(1L, new ArrayList<>(), 2L, new ArrayList<>(List.of(line(20L)))));

        assertThat(deleted).containsExactly(List.of(10L));
    }

    @Test
    void severalParentsInOneWrite_aChildDeletedFromOneIsRefusedOnlyUnderThatOne() {
        twoClaims();

        // Line 11 was deleted from claim 1: sent back under claim 1 it is refused, naming it.
        assertThatThrownBy(() -> writeAll(Map.of(1L, new ArrayList<>(List.of(line(10L), line(11L))),
                2L, new ArrayList<>(List.of(line(20L))))))
                .hasMessageContaining("has been deleted").hasMessageContaining("11");
        // Under claim 2 it is no child of that claim's, deleted or not: not refused as deleted, it is a new line.
        writeAll(Map.of(1L, new ArrayList<>(List.of(line(10L))), 2L, new ArrayList<>(List.of(line(20L), line(11L)))));
        assertThat(deleted).isEmpty();
    }

    @Test
    void aPatchCreatingUnderTheIdOfADeletedChildIsRefused() {
        assertThatThrownBy(() -> write(new HashMap<>(Map.of("Create", new ArrayList<>(List.of(line(11L)))))))
                .hasMessageContaining("has been deleted");
        reflect.verify(() -> ReflectTool.createList(anyString(), anyList()), Mockito.never());
    }

    @Test
    void aPatchCreatingANewChildStillCreatesIt() {
        write(new HashMap<>(Map.of("Create", new ArrayList<>(List.of(line(null))))));

        reflect.verify(() -> ReflectTool.createList(eq(RELATED), anyList()));
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
    void aNullValueDeletesOnlyTheLiveChildren() {
        write(null);

        assertThat(deleted).containsExactly(List.of(10L));
    }

    @Test
    void theDiffReadsWhetherEachChildIsDeleted_pastSoftDeleteAndActiveControl() {
        write(new ArrayList<>(List.of(line(10L))));

        ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
        reflect.verify(() -> ReflectTool.searchListIgnoringRowScope(eq(RELATED), sent.capture()));
        assertThat(sent.getValue().getFields()).contains("id", "claimId", "deleted");
        assertThat(sent.getValue().getFilterControl().isSkipSoftDelete()).isTrue();
        assertThat(sent.getValue().getFilterControl().isSkipActiveControl()).isTrue();
    }

    /**
     * Children that are not soft-deleted have no deleted flag to read: every child found is live, whatever a column
     * of that name holds, and a list sent back without one deletes it.
     */
    @Test
    void childrenWithoutSoftDeleteAreAllLive() {
        models.when(() -> ModelManager.isSoftDeleted(RELATED)).thenReturn(false);

        write(new ArrayList<>(List.of(line(10L))));

        assertThat(deleted).containsExactly(List.of(11L));
        ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
        reflect.verify(() -> ReflectTool.searchListIgnoringRowScope(eq(RELATED), sent.capture()));
        assertThat(sent.getValue().getFields()).doesNotContain("deleted");
    }

    @Test
    void childrenWithoutSoftDeleteMayBeSentBackAndPatched() {
        models.when(() -> ModelManager.isSoftDeleted(RELATED)).thenReturn(false);

        write(new HashMap<>(Map.of("Update", new ArrayList<>(List.of(line(11L))))));

        assertThat(updated).hasSize(1);
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
