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
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.jdbc.database.builder.WhereBuilder;
import io.softa.framework.orm.jdbc.database.dialect.PostgreSQLDialect;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    /**
     * The expansion's query as the database is asked it, on a related model that is both soft-deleted and under
     * active control: the deleted rows are filtered out, the inactive ones are not.
     */
    @Test
    void theSqlFiltersOutTheDeletedButKeepsTheInactive() {
        FlexQuery sent = expansionQueryFor(new SubQuery(List.of("amount")));

        String where = whereOf(sent);

        assertThat(where).contains("t.claim_id").contains("t.deleted").doesNotContain("t.active");
    }

    private static String whereOf(FlexQuery query) {
        Object previousDialect = ReflectionTestUtils.getField(DBUtil.class, "singleDbDialect");
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", new PostgreSQLDialect());
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            MetaModel related = new MetaModel();
            ReflectionTestUtils.setField(related, "modelName", RELATED);
            ReflectionTestUtils.setField(related, "tableName", "claim_line");
            models.when(() -> ModelManager.getModel(RELATED)).thenReturn(related);
            models.when(() -> ModelManager.isSoftDeleted(RELATED)).thenReturn(true);
            models.when(() -> ModelManager.isActiveControl(RELATED)).thenReturn(true);
            models.when(() -> ModelManager.isMultiTenantControl(anyString())).thenReturn(false);
            models.when(() -> ModelManager.isTimelineModel(anyString())).thenReturn(false);
            stubColumn(models, "id", "id", FieldType.LONG);
            stubColumn(models, "claimId", "claim_id", FieldType.LONG);
            stubColumn(models, "deleted", "deleted", FieldType.BOOLEAN);
            stubColumn(models, "active", "active", FieldType.BOOLEAN);
            SqlWrapper sqlWrapper = new SqlWrapper(RELATED);
            new WhereBuilder(sqlWrapper, query).build();
            Object where = ReflectionTestUtils.getField(sqlWrapper, "whereClause");
            return where == null ? "" : where.toString();
        } finally {
            ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", previousDialect);
        }
    }

    private static void stubColumn(MockedStatic<ModelManager> models, String fieldName, String columnName,
                                   FieldType fieldType) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", RELATED);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "columnName", columnName);
        ReflectionTestUtils.setField(field, "fieldType", fieldType);
        models.when(() -> ModelManager.existField(RELATED, fieldName)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(RELATED, fieldName)).thenReturn(field);
    }

    /** A read that does not expand the field: it shows the children by their display names. */
    private static FlexQuery displayNameQuery() {
        try (MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class);
             MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            reflect.when(() -> ReflectTool.searchNameIgnoringRowScope(eq(RELATED), any(FlexQuery.class)))
                    .thenReturn(List.of());
            FlexQuery mainQuery = new FlexQuery();
            mainQuery.setConvertType(ConvertType.DISPLAY);

            new OneToManyProcessor(oneToMany(), AccessType.READ, mainQuery).batchProcessOutputRows(mainRows());

            ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
            reflect.verify(() -> ReflectTool.searchNameIgnoringRowScope(eq(RELATED), sent.capture()));
            return sent.getValue();
        }
    }

    @Test
    void theDisplayNamesLeaveOutTheDeleted() {
        FlexQuery sent = displayNameQuery();

        assertThat(sent.getFilterControl().isSkipSoftDelete()).isFalse();
        assertThat(sent.getFilterControl().isSkipActiveControl()).isTrue();
    }
}
