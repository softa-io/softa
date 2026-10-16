package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.ModelServiceImpl;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which read a relation expansion reaches related rows through, for each of the three relation
 * processors, in the three cases that decide it:
 * <ul>
 *   <li><b>no subQuery</b> — labels only, across the caller's row range: a referenced row's label is
 *       shown wherever the reference is;</li>
 *   <li><b>a subQuery onto a model with a row range of its own</b> — read as a direct read would, the
 *       range applied: the subQuery names its own fields and filters, and with the range crossed it
 *       reads any field of any row the caller is merely shown a reference to;</li>
 *   <li><b>a subQuery onto an owned child</b> — across the range: the child is as visible as the parent
 *       row being expanded, and the range would fail closed on it and blank the parent's sub-rows.</li>
 * </ul>
 *
 * <p>Runs the real chain {@code processor → ReflectTool → ModelServiceImpl}; only the database and the
 * permission service are mocked, so what is asserted is which permission call the related model's read
 * went through — {@code appendScopeAccessFilters} (range applied) or
 * {@code appendScopeAccessFiltersIgnoringRowScope} (range crossed).
 */
class RelationSubQueryRowScopeTest {

    private static final String MAIN = "Employee";
    /** A related model the caller's range applies to in its own right. */
    private static final String ANCHORED = "Colleague";
    /** A related model visible only through the row that owns it. */
    private static final String OWNED = "Profile";
    private static final String JOIN = "TeamMember";

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final JdbcService<Long> jdbcService = mock(JdbcService.class);
    private final Map<String, List<Map<String, Object>>> tables = new HashMap<>();

    private MockedStatic<ModelManager> modelManager;
    private MockedStatic<SpringContextUtils> spring;

    @BeforeEach
    void wire() {
        ModelServiceImpl<Long> service = new ModelServiceImpl<>();
        VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
        VersioningStrategy strategy = mock(VersioningStrategy.class);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "versioning", versioning);
        when(versioning.of(anyString())).thenReturn(strategy);
        when(strategy.scopeRead(anyString(), any(FlexQuery.class))).thenAnswer(i -> new Filters());

        when(permissionService.filterReadableFields(anyString(), any(), eq(AccessType.READ)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.appendScopeAccessFilters(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.appendScopeAccessFiltersIgnoringRowScope(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.hasOwnRowScope(ANCHORED)).thenReturn(true);
        when(permissionService.hasOwnRowScope(OWNED)).thenReturn(false);
        when(permissionService.hasOwnRowScope(JOIN)).thenReturn(false);
        when(jdbcService.selectByFilter(anyString(), any(FlexQuery.class)))
                .thenAnswer(i -> copyOf(tables.getOrDefault(i.<String>getArgument(0), List.of())));

        modelManager = mockStatic(ModelManager.class);
        MetaField longId = mock(MetaField.class);
        when(longId.getFieldType()).thenReturn(FieldType.LONG);
        modelManager.when(() -> ModelManager.getModelField(anyString(), eq("id"))).thenReturn(longId);
        modelManager.when(() -> ModelManager.getModelDisplayName(anyString())).thenReturn(List.of("name"));
        modelManager.when(() -> ModelManager.getModel(anyString())).thenReturn(mock(MetaModel.class));

        spring = mockStatic(SpringContextUtils.class);
        spring.when(() -> SpringContextUtils.getBeanByClass(ModelServiceImpl.class)).thenReturn(service);
    }

    @AfterEach
    void unwire() {
        spring.close();
        modelManager.close();
    }

    // ───────────────────────────── XToOne ─────────────────────────────


    private static final String TO_ONE_FIELD = "relatedId";

    private void expandToOne(String related, SubQuery subQuery) {
        tables.put(related, List.of(row(2L, "name", "Lee", "dateOfBirth", "2000-11-30")));
        MetaField field = field(FieldType.MANY_TO_ONE, related);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1L, TO_ONE_FIELD, 2L)));
        new XToOneGroupProcessor(field, AccessType.READ, mainQuery(TO_ONE_FIELD, subQuery)).batchProcessOutputRows(rows);
    }

    @Test
    void toOne_noSubQuery_readsTheLabelAcrossTheRange() {
        expandToOne(ANCHORED, null);

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).hasOwnRowScope(anyString());
    }

    @Test
    void toOne_subQueryOntoAnAnchoredModel_appliesTheRange() {
        expandToOne(ANCHORED, new SubQuery(List.of("dateOfBirth")));

        verify(permissionService).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
        // Returned by the scoped read, so no label fallback is needed.
        verify(permissionService, never()).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
    }

    @Test
    void toOne_subQueryOntoAnOwnedChild_crossesTheRange() {
        expandToOne(OWNED, new SubQuery(List.of("dateOfBirth")));

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(OWNED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFilters(eq(OWNED), any(Filters.class));
    }

    @Test
    void toOne_subQueryOntoARowOutsideTheRange_fallsBackToItsLabelAlone() {
        MetaField field = field(FieldType.MANY_TO_ONE, ANCHORED);
        // The scoped read returns nothing (the row is out of range); the label read returns the row.
        when(jdbcService.selectByFilter(eq(ANCHORED), any(FlexQuery.class))).thenAnswer(i -> {
            FlexQuery q = i.getArgument(1);
            return q.getFields().contains("dateOfBirth") ? new ArrayList<>() : copyOf(List.of(row(2L, "name", "Lee")));
        });
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1L, TO_ONE_FIELD, 2L)));

        new XToOneGroupProcessor(field, AccessType.READ, mainQuery(TO_ONE_FIELD, new SubQuery(List.of("dateOfBirth"))))
                .batchProcessOutputRows(rows);

        ArgumentCaptor<FlexQuery> label = ArgumentCaptor.forClass(FlexQuery.class);
        verify(jdbcService, atLeastOnce()).selectByFilter(eq(ANCHORED), label.capture());
        assertThat(label.getAllValues().getLast().getFields()).containsExactlyInAnyOrder("id", "name");
        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
        assertThat(rows.getFirst().get(TO_ONE_FIELD)).isEqualTo(Map.of("id", 2L, "name", "Lee"));
    }

    // ───────────────────────────── OneToMany ─────────────────────────────


    private static final String TO_MANY_FIELD = "children";

    private void expandToMany(String related, SubQuery subQuery) {
        tables.put(related, List.of(row(2L, "parentId", 1L, "name", "Lee")));
        MetaField field = field(FieldType.ONE_TO_MANY, related);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1L)));
        new OneToManyProcessor(field, AccessType.READ, mainQuery(TO_MANY_FIELD, subQuery)).batchProcessOutputRows(rows);
    }

    @Test
    void toMany_noSubQuery_readsTheLabelAcrossTheRange() {
        expandToMany(ANCHORED, null);

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).hasOwnRowScope(anyString());
    }

    @Test
    void toMany_subQueryOntoAnAnchoredModel_appliesTheRange() {
        expandToMany(ANCHORED, new SubQuery(List.of("dateOfBirth")));

        verify(permissionService).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
    }

    @Test
    void toMany_subQueryOntoAnOwnedChild_crossesTheRange() {
        expandToMany(OWNED, new SubQuery(List.of("dateOfBirth")));

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(OWNED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFilters(eq(OWNED), any(Filters.class));
    }

    @Test
    void toMany_countOntoAnAnchoredModel_countsOnlyTheRowsInRange() {
        SubQuery count = new SubQuery();
        count.setCount(true);
        tables.put(ANCHORED, List.of(row(null, "parentId", 1L, "count", 1L)));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1L)));

        new OneToManyProcessor(field(FieldType.ONE_TO_MANY, ANCHORED), AccessType.READ, mainQuery(TO_MANY_FIELD, count))
                .batchProcessOutputRows(rows);

        verify(permissionService).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
    }

    // ───────────────────────────── ManyToMany ─────────────────────────────


    private static final String MANY_TO_MANY_FIELD = "members";

    private void expandManyToMany(String right, SubQuery subQuery) {
        tables.put(JOIN, List.of(row(9L, "teamId", 1L, "memberId", 2L)));
        tables.put(right, List.of(row(2L, "name", "Lee")));
        MetaField field = field(FieldType.MANY_TO_MANY, right);
        when(field.getJoinModel()).thenReturn(JOIN);
        when(field.getJoinLeft()).thenReturn("teamId");
        when(field.getJoinRight()).thenReturn("memberId");
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1L)));
        new ManyToManyProcessor(field, AccessType.READ, mainQuery(MANY_TO_MANY_FIELD, subQuery)).batchProcessOutputRows(rows);
    }

    @Test
    void manyToMany_noSubQuery_readsTheLabelAcrossTheRange() {
        expandManyToMany(ANCHORED, null);

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).hasOwnRowScope(anyString());
    }

    @Test
    void manyToMany_subQueryOntoAnAnchoredModel_appliesTheRange() {
        expandManyToMany(ANCHORED, new SubQuery(List.of("dateOfBirth")));

        verify(permissionService).appendScopeAccessFilters(eq(ANCHORED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFiltersIgnoringRowScope(eq(ANCHORED), any(Filters.class));
    }

    @Test
    void manyToMany_subQueryOntoAnOwnedModel_crossesTheRange() {
        expandManyToMany(OWNED, new SubQuery(List.of("dateOfBirth")));

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(OWNED), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFilters(eq(OWNED), any(Filters.class));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Mocked: its setters belong to the metadata package. */
    private static MetaField field(FieldType type, String related) {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MAIN);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(related);
        String name = switch (type) {
            case ONE_TO_MANY -> TO_MANY_FIELD;
            case MANY_TO_MANY -> MANY_TO_MANY_FIELD;
            default -> TO_ONE_FIELD;
        };
        when(field.getFieldName()).thenReturn(name);
        when(field.getRelatedField()).thenReturn("parentId");
        return field;
    }

    private static FlexQuery mainQuery(String field, SubQuery subQuery) {
        FlexQuery query = new FlexQuery();
        query.setConvertType(ConvertType.REFERENCE);
        if (subQuery != null) {
            SubQueries subQueries = new SubQueries();
            subQueries.setQueryMap(new HashMap<>(Map.of(field, subQuery)));
            query.setSubQueries(subQueries);
        }
        return query;
    }

    private static Map<String, Object> row(Serializable id, Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        if (id != null) {
            row.put("id", id);
        }
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static List<Map<String, Object>> copyOf(Collection<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        rows.forEach(r -> out.add(new HashMap<>(r)));
        return out;
    }
}
