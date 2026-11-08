package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
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
 * A OneToMany expansion reads its rows as any read does: a soft-deleted row is gone. It came back
 * under its parent after a delete, though a read of the related model itself no longer found it. An
 * inactive row stays, since a parent still lists the children it has switched off.
 */
class OneToManyDeletedRowsTest {

    private static final String MAIN = "Claim";
    private static final String RELATED = "ClaimLine";
    private static final String FIELD = "lines";

    private static MetaField oneToMany() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MAIN);
        when(field.getFieldName()).thenReturn(FIELD);
        when(field.getRelatedModel()).thenReturn(RELATED);
        when(field.getRelatedField()).thenReturn("claimId");
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

    private static FlexQuery expansionQueryFor(SubQuery subQuery) {
        try (MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class);
             MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            MetaModel related = new MetaModel();
            ReflectionTestUtils.setField(related, "modelName", RELATED);
            models.when(() -> ModelManager.getModel(RELATED)).thenReturn(related);
            reflect.when(() -> ReflectTool.searchRelatedRows(eq(RELATED), any(FlexQuery.class)))
                    .thenReturn(List.of());

            new OneToManyProcessor(oneToMany(), AccessType.READ, mainQuery(subQuery))
                    .batchProcessOutputRows(mainRows());

            ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
            reflect.verify(() -> ReflectTool.searchRelatedRows(eq(RELATED), sent.capture()));
            return sent.getValue();
        }
    }

    @Test
    void theRowsLeaveOutTheDeleted() {
        FlexQuery sent = expansionQueryFor(new SubQuery(List.of("amount")));

        assertThat(sent.getFilterControl().isSkipSoftDelete()).isFalse();
        assertThat(sent.getFilterControl().isSkipActiveControl()).isTrue();
    }

    @Test
    void theCountCountsTheRowsTheListWouldShow() {
        SubQuery subQuery = new SubQuery();
        subQuery.setCount(true);

        FlexQuery sent = expansionQueryFor(subQuery);

        assertThat(sent.getFilterControl().isSkipSoftDelete()).isFalse();
        assertThat(sent.getFilterControl().isSkipActiveControl()).isTrue();
    }
}
