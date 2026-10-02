package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A OneToMany expansion onto a timeline model, asked for every slice.
 *
 * <p>The related model is read like any other, so the expansion was clamped to today the way a
 * top-level read is. A child table listing the versions of something showed only the current one,
 * and a version starting next month — the thing such a table is opened to check — was absent without
 * a word. The top-level query had a switch for this; a sub query had none.
 */
class OneToManyAcrossTimelineTest {

    private static final String MAIN = "Employee";
    private static final String RELATED = "SalaryVersion";
    private static final String FIELD = "salaryVersions";

    /** Mocked: its setters belong to the metadata package, and only these four are read here. */
    private static MetaField oneToMany() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MAIN);
        when(field.getFieldName()).thenReturn(FIELD);
        when(field.getRelatedModel()).thenReturn(RELATED);
        when(field.getRelatedField()).thenReturn("employeeId");
        return field;
    }

    private static FlexQuery mainQuery(SubQuery subQuery) {
        FlexQuery query = new FlexQuery();
        SubQueries subQueries = new SubQueries();
        subQueries.setQueryMap(new HashMap<>(Map.of(FIELD, subQuery)));
        query.setSubQueries(subQueries);
        return query;
    }

    private static List<Map<String, Object>> mainRows() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        return new ArrayList<>(List.of(row));
    }

    /** The query the expansion sent to the related model. */
    private static FlexQuery expansionQueryFor(SubQuery subQuery) {
        try (MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class)) {
            reflect.when(() -> ReflectTool.searchListIgnoringRowScope(eq(RELATED), any(FlexQuery.class)))
                    .thenReturn(List.of());

            new OneToManyProcessor(oneToMany(), AccessType.READ, mainQuery(subQuery))
                    .batchProcessOutputRows(mainRows());

            ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
            reflect.verify(() -> ReflectTool.searchListIgnoringRowScope(eq(RELATED), sent.capture()));
            return sent.getValue();
        }
    }

    @Test
    void askingForEverySliceLiftsTheClamp() {
        SubQuery subQuery = new SubQuery(List.of("amount"));
        subQuery.setAcrossTimeline(true);

        assertThat(expansionQueryFor(subQuery).isAcrossTimeline()).isTrue();
    }

    @Test
    void notAskingKeepsTheCurrentSliceOnly() {
        // The default does not move. A child list that wants each related entity as it stands today
        // is just as legitimate, and the readers that exist rely on it.
        assertThat(expansionQueryFor(new SubQuery(List.of("amount"))).isAcrossTimeline()).isFalse();
    }

    @Test
    void theCountCountsWhatTheListWouldShow() {
        // A count clamped to today standing in for a list that is not would disagree with it.
        SubQuery subQuery = new SubQuery();
        subQuery.setCount(true);
        subQuery.setAcrossTimeline(true);

        assertThat(expansionQueryFor(subQuery).isAcrossTimeline()).isTrue();
    }
}
