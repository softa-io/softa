package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The lines and tags a record's read expands come back in the order they were entered.
 *
 * <p>They are read without paging, so the paged fallback (newest first) never reaches them. Naming no order, on a
 * model declaring none, they came back in whatever order the database returned, and an edited line could jump to the
 * end of its table. The two relation processors fill in the order themselves: {@code id} ascending (then
 * {@code sliceId} on a timeline model), unless the read or the model names one.
 */
class RelationEntryOrderTest {

    private static final String MAIN = "ExpenseClaim";
    private static final String LINES = "ExpenseLine";
    private static final String LINES_FIELD = "lines";

    private static final String TAGGED = "UserAccount";
    private static final String TAG = "Role";
    private static final String JOIN = "UserRoleRel";
    private static final String TAGS_FIELD = "roles";

    private static MetaField oneToMany() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MAIN);
        when(field.getFieldName()).thenReturn(LINES_FIELD);
        when(field.getRelatedModel()).thenReturn(LINES);
        when(field.getRelatedField()).thenReturn("claimId");
        return field;
    }

    private static MetaField manyToMany() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(TAGGED);
        when(field.getFieldName()).thenReturn(TAGS_FIELD);
        when(field.getRelatedModel()).thenReturn(TAG);
        when(field.getJoinModel()).thenReturn(JOIN);
        when(field.getJoinLeft()).thenReturn("userId");
        when(field.getJoinRight()).thenReturn("roleId");
        return field;
    }

    private static FlexQuery mainQuery(String field, SubQuery subQuery) {
        FlexQuery query = new FlexQuery();
        SubQueries subQueries = new SubQueries();
        subQueries.setQueryMap(new HashMap<>(Map.of(field, subQuery)));
        query.setSubQueries(subQueries);
        return query;
    }

    private static List<Map<String, Object>> mainRows() {
        Map<String, Object> row = new HashMap<>();
        row.put(ModelConstant.ID, 7L);
        return new ArrayList<>(List.of(row));
    }

    /** The model, with the order it declares (null for none), and whether it is a timeline model. */
    private static void stubModel(MockedStatic<ModelManager> models, String name, Orders defaultOrder, boolean timeline) {
        MetaModel model = new MetaModel();
        ReflectionTestUtils.setField(model, "modelName", name);
        if (defaultOrder != null) {
            ReflectionTestUtils.setField(model, "defaultOrder", defaultOrder);
        }
        models.when(() -> ModelManager.getModel(name)).thenReturn(model);
        models.when(() -> ModelManager.isTimelineModel(name)).thenReturn(timeline);
    }

    /** The query the one-to-many expansion sent for the lines of a line model declaring that order. */
    private static FlexQuery linesQueryFor(SubQuery subQuery, Orders defaultOrder, boolean timeline) {
        try (MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class);
             MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            stubModel(models, LINES, defaultOrder, timeline);
            reflect.when(() -> ReflectTool.searchRelatedRows(eq(LINES), any(FlexQuery.class))).thenReturn(List.of());

            new OneToManyProcessor(oneToMany(), AccessType.READ, mainQuery(LINES_FIELD, subQuery))
                    .batchProcessOutputRows(mainRows());

            ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
            reflect.verify(() -> ReflectTool.searchRelatedRows(eq(LINES), sent.capture()));
            return sent.getValue();
        }
    }

    @Test
    void theLinesReadInTheOrderTheyWereEntered() {
        assertThat(linesQueryFor(new SubQuery(List.of("amount")), null, false).getOrders()).hasToString("id ASC");
    }

    /** The slices of one timeline record share its id: sliceId orders them among themselves. */
    @Test
    void timelineLinesEndWithTheSlice() {
        assertThat(linesQueryFor(new SubQuery(List.of("amount")), null, true).getOrders())
                .hasToString("id ASC, sliceId ASC");
    }

    @Test
    void anOrderTheReadNamesStillWins() {
        SubQuery subQuery = new SubQuery(List.of("amount"));
        subQuery.setOrders(Orders.ofDesc("expenseDate"));

        assertThat(linesQueryFor(subQuery, null, false).getOrders()).hasToString("expenseDate DESC");
    }

    /** Left unset, so the SQL builder applies the order the line model declares, as on any read of it. */
    @Test
    void aLineModelThatDeclaresItsOrderKeepsIt() {
        assertThat(linesQueryFor(new SubQuery(List.of("amount")), Orders.ofAsc("expenseDate"), false).getOrders())
                .isNull();
    }

    @Test
    void theTagsFollowTheJoinRowsInTheOrderTheyWereAdded() {
        try (MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class);
             MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            stubModel(models, JOIN, null, false);
            reflect.when(() -> ReflectTool.searchListIgnoringRowScope(eq(JOIN), any(FlexQuery.class)))
                    .thenReturn(List.of(new HashMap<>(Map.of(ModelConstant.ID, 900L, "userId", 7L, "roleId", 100L))));
            reflect.when(() -> ReflectTool.searchRelatedRows(eq(TAG), any(FlexQuery.class)))
                    .thenReturn(List.of(Map.of(ModelConstant.ID, 100L)));

            new ManyToManyProcessor(manyToMany(), AccessType.READ, mainQuery(TAGS_FIELD, new SubQuery(List.of("name"))))
                    .batchProcessOutputRows(mainRows());

            ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
            reflect.verify(() -> ReflectTool.searchListIgnoringRowScope(eq(JOIN), sent.capture()));
            assertThat(sent.getValue().getOrders()).hasToString("id ASC");
        }
    }
}
