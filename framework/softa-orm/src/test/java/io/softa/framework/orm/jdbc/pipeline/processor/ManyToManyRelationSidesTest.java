package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * A many-to-many whose join model links its two sides through relations — {@code UserRoleRel} with a
 * {@code userId} and a {@code roleId} — rather than plain id columns.
 *
 * <p>Ids arrive from a client as strings, and the join rows are read back with Long ids. Until a
 * relation's ids were converted to the type of the ids they reference, the two never compared equal:
 * a Remove matched nothing and was dropped without a word, an Add did not see the link already there
 * and wrote it again, and a full list rewrote every link as new.
 */
class ManyToManyRelationSidesTest {

    private static final String MAIN = "UserAccount";
    private static final String RELATED = "Role";
    private static final String JOIN = "UserRoleRel";
    private static final String FIELD = "roles";
    private static final String LEFT = "userId";
    private static final String RIGHT = "roleId";

    private static final long USER = 7L;
    /** The join row that links the user to role 100. */
    private static final long LINK_100 = 900L;

    private static MetaField manyToMany() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MAIN);
        when(field.getFieldName()).thenReturn(FIELD);
        when(field.getRelatedModel()).thenReturn(RELATED);
        when(field.getJoinModel()).thenReturn(JOIN);
        when(field.getJoinLeft()).thenReturn(LEFT);
        when(field.getJoinRight()).thenReturn(RIGHT);
        return field;
    }

    private static MetaField roleIdOnTheJoin() {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        when(field.getRelatedFieldType()).thenReturn(FieldType.LONG);
        when(field.getRelatedModel()).thenReturn(RELATED);
        return field;
    }

    private static Map<String, Object> user(Object roles) {
        Map<String, Object> row = new HashMap<>();
        row.put(ModelConstant.ID, USER);
        row.put(FIELD, roles);
        return row;
    }

    /** The user is linked to role 100 already; the join row reads back with Long ids. */
    private static void linkedToRole100(MockedStatic<ReflectTool> reflect) {
        Map<String, Object> link = Map.of(ModelConstant.ID, LINK_100, LEFT, USER, RIGHT, 100L);
        reflect.when(() -> ReflectTool.searchListIgnoringRowScope(eq(JOIN), any(FlexQuery.class)))
                .thenReturn(List.of(link));
        reflect.when(() -> ReflectTool.searchRelatedRows(eq(RELATED), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of(ModelConstant.ID, 200L)));
    }

    private static void update(Object roles) {
        new ManyToManyProcessor(manyToMany(), AccessType.UPDATE, null)
                .batchProcessInputRows(new ArrayList<>(List.of(user(roles))));
    }

    @Test
    void removingALinkByAStringIdDeletesIt() {
        MetaField roleId = roleIdOnTheJoin();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class)) {
            models.when(() -> ModelManager.getModelField(JOIN, RIGHT)).thenReturn(roleId);
            linkedToRole100(reflect);

            update(Map.of("Remove", List.of("100")));

            reflect.verify(() -> ReflectTool.deleteList(JOIN, List.<Serializable>of(LINK_100)));
            reflect.verify(() -> ReflectTool.createList(eq(JOIN), anyList()), never());
        }
    }

    @Test
    void addingALinkAlreadyThereByAStringIdWritesNothing() {
        MetaField roleId = roleIdOnTheJoin();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class)) {
            models.when(() -> ModelManager.getModelField(JOIN, RIGHT)).thenReturn(roleId);
            linkedToRole100(reflect);

            update(Map.of("Add", List.of("100")));

            reflect.verify(() -> ReflectTool.createList(eq(JOIN), anyList()), never());
            reflect.verify(() -> ReflectTool.deleteList(eq(JOIN), anyList()), never());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFullListOfStringIdsKeepsTheLinksItRepeats() {
        MetaField roleId = roleIdOnTheJoin();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class)) {
            models.when(() -> ModelManager.getModelField(JOIN, RIGHT)).thenReturn(roleId);
            linkedToRole100(reflect);
            reflect.when(() -> ReflectTool.createList(eq(JOIN), anyList())).thenReturn(List.of());

            update(List.of("100", "200"));

            // Role 100 was linked and stays; only role 200 is new, and nothing is unlinked.
            ArgumentCaptor<List<Map<String, Object>>> created = ArgumentCaptor.forClass(List.class);
            reflect.verify(() -> ReflectTool.createList(eq(JOIN), created.capture()));
            assertThat(created.getValue()).containsExactly(Map.of(LEFT, USER, RIGHT, 200L));
            reflect.verify(() -> ReflectTool.deleteList(eq(JOIN), anyList()), never());
        }
    }
}
