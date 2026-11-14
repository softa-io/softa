package io.softa.starter.flow.runtime.context;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.starter.flow.runtime.engine.FlowGatewayRouter;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static io.softa.starter.flow.runtime.context.RecordModelFixture.RELATED;
import static io.softa.starter.flow.runtime.context.RecordModelFixture.RELATED_ROUTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A rule reads through the record's relations, up to two on — {@code departmentId.costCenter},
 * {@code departmentId.parentId.costCenter} — to a field the related record would route on. A start reads those values
 * from the related records, only the ones the flow's rules name, and a route compares each by the type of the field it
 * reaches.
 */
class RecordVariablesPathsTest {

    private MockedStatic<ModelManager> modelManager;
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);

    @BeforeEach
    void setUp() {
        modelManager = RecordModelFixture.mockModelManager();
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aRuleReadsThroughRoutedRelationsToARoutedField() {
        assertEquals(List.of("departmentId"), names(RecordVariables.routedRelations(MODEL)));
        for (String leaf : RELATED_ROUTED) {
            assertEquals(leaf, RecordVariables.routedPathField(MODEL, "departmentId." + leaf).getFieldName());
        }
        // One hop on, the related record's identity, bookkeeping, dynamic and sensitive fields stay out as they do on
        // the record itself.
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.id"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.updatedTime"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.headcount"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.budget"));
        // Two hops, each through a routed relation; not three, and never through a field that is no relation.
        assertEquals("costCenter", RecordVariables.routedPathField(MODEL, "departmentId.parentId.costCenter").getFieldName());
        assertEquals("parentId", RecordVariables.routedPathField(MODEL, "departmentId.parentId.parentId").getFieldName());
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.parentId.budget"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.parentId.parentId.costCenter"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId.costCenter.length"));
        assertNull(RecordVariables.routedPathField(MODEL, "reason.costCenter"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId"));
        assertNull(RecordVariables.routedPathField(MODEL, "departmentId..costCenter"));
    }

    @Test
    void theFlowsPathsAreTheDottedNamesItsRulesRead() {
        Filters rule = Filters.of(List.of(List.of("days", ">", 3), "AND", List.of("departmentId.costCenter", "=", "CC1"),
                "AND", List.of("departmentId.level", ">=", 4)));

        assertEquals(Set.of("departmentId.costCenter", "departmentId.level"), RecordVariables.pathsIn(rule));
        assertEquals(Set.of(), RecordVariables.pathsIn(null));
    }

    @Test
    void aStartReadsThePathsItsRulesNameFromTheRelatedRecord() {
        when(modelService.searchList(eq(RELATED), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("costCenter", "CC1", "establishedOn", LocalDate.of(2020, 1, 6))));

        Map<String, Object> values = RecordVariables.readPaths(modelService, MODEL, "5",
                Map.of("departmentId", 30L, "days", 3),
                List.of("departmentId.costCenter", "departmentId.establishedOn", "departmentId.level",
                        "departmentId.budget"));

        // A date as ISO text, as the record's own; a path the related record holds nothing for is left out.
        assertEquals(Map.of("departmentId.costCenter", "CC1", "departmentId.establishedOn", "2020-01-06"), values);
        ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq(RELATED), query.capture());
        // One read of the related record, for the paths a rule may read — the sensitive budget is not one.
        assertEquals(Set.of("costCenter", "establishedOn", "level"), Set.copyOf(query.getValue().getFields()));
        assertTrue(query.getValue().getFilters().toString().contains("30"));
        verify(modelService, never()).getById(any(), any(), anyCollection());
    }

    @Test
    void aRelationTheRowDoesNotCarryIsReadFromTheStoredRecord() {
        when(modelService.getById(eq(MODEL), eq(5L), anyCollection())).thenReturn(Optional.of(Map.of("departmentId", 30L)));
        when(modelService.searchList(eq(RELATED), any(FlexQuery.class))).thenReturn(List.of(Map.of("level", 4)));

        // Of an update, an entity change carries only what it changed: here the reason.
        Map<String, Object> values = RecordVariables.readPaths(modelService, MODEL, "5", Map.of("reason", "x"),
                List.of("departmentId.level"));

        assertEquals(Map.of("departmentId.level", 4), values);
        verify(modelService).getById(eq(MODEL), eq(5L), eq(List.of("departmentId")));
    }

    @Test
    void aRelationIsFollowedByTheIdItHoldsHoweverItIsHeld() {
        when(modelService.searchList(eq(RELATED), any(FlexQuery.class))).thenReturn(List.of(Map.of("level", 2)));
        ModelReference reference = new ModelReference();
        reference.setId(31L);

        assertEquals(Map.of("departmentId.level", 2), RecordVariables.readPaths(modelService, MODEL, null,
                Map.of("departmentId", reference), List.of("departmentId.level")));
        assertEquals(Map.of("departmentId.level", 2), RecordVariables.readPaths(modelService, MODEL, null,
                Map.of("departmentId", Map.of("id", 32L, "displayName", "Sales")), List.of("departmentId.level")));
        ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService, org.mockito.Mockito.times(2)).searchList(eq(RELATED), query.capture());
        assertTrue(query.getAllValues().get(0).getFilters().toString().contains("31"));
        assertTrue(query.getAllValues().get(1).getFilters().toString().contains("32"));
    }

    @Test
    void aPathOfARelationWithNoValueHasNoValue() {
        Map<String, Object> row = new HashMap<>();
        row.put("departmentId", null);

        assertEquals(Map.of(), RecordVariables.readPaths(modelService, MODEL, "5", row, List.of("departmentId.level")));
        // Nor does a name that is no path a rule may read, so nothing is read for it.
        assertEquals(Map.of(), RecordVariables.readPaths(modelService, MODEL, "5", Map.of("departmentId", 30L),
                List.of("departmentId.budget", "departmentId.parentId.parentId.costCenter")));
        verifyNoInteractions(modelService);
    }

    /**
     * Two hops are two reads: the related record — for its own fields and the next relation's value — then the record
     * that one points at. A path whose second relation has no value has none either.
     */
    @Test
    void aStartReadsAPathTwoHopsOnRecordByRecord() {
        when(modelService.searchList(eq(RELATED), any(FlexQuery.class))).thenAnswer(call -> {
            FlexQuery query = call.getArgument(1);
            return query.getFilters().toString().contains("30")
                    ? List.of(Map.of("level", 4, "parentId", 20L))
                    : List.of(Map.of("costCenter", "CC0", "establishedOn", LocalDate.of(2019, 3, 1)));
        });

        Map<String, Object> values = RecordVariables.readPaths(modelService, MODEL, "5", Map.of("departmentId", 30L),
                List.of("departmentId.level", "departmentId.parentId", "departmentId.parentId.costCenter",
                        "departmentId.parentId.establishedOn"));

        assertEquals(Map.of(
                "departmentId.level", 4,
                "departmentId.parentId", 20L,
                "departmentId.parentId.costCenter", "CC0",
                "departmentId.parentId.establishedOn", "2019-03-01"), values);
        ArgumentCaptor<FlexQuery> queries = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService, org.mockito.Mockito.times(2)).searchList(eq(RELATED), queries.capture());
        // The first read asks for the field read there and the relation followed on, once each.
        assertEquals(Set.of("level", "parentId"), Set.copyOf(queries.getAllValues().get(0).getFields()));
        assertTrue(queries.getAllValues().get(1).getFilters().toString().contains("20"));
        assertEquals(Set.of("costCenter", "establishedOn"), Set.copyOf(queries.getAllValues().get(1).getFields()));
    }

    @Test
    void aPathsSecondRelationWithNoValueHasNoValue() {
        when(modelService.searchList(eq(RELATED), any(FlexQuery.class))).thenReturn(List.of(Map.of("level", 1)));

        assertEquals(Map.of("departmentId.level", 1), RecordVariables.readPaths(modelService, MODEL, "5",
                Map.of("departmentId", 30L), List.of("departmentId.level", "departmentId.parentId.costCenter")));
        verify(modelService, org.mockito.Mockito.times(1)).searchList(eq(RELATED), any(FlexQuery.class));
    }

    @Test
    void aPathsNumbersComeBackAsNumbers() {
        Map<String, Object> variables = new HashMap<>(Map.of(
                "departmentId.level", "4",
                "departmentId.parentId", "906576038580977724",
                "departmentId.costCenter", "007",
                "departmentId.parentId.level", "2"));

        RecordVariables.restoreNumbers(MODEL, variables);

        assertEquals(4, variables.get("departmentId.level"));
        assertEquals(906576038580977724L, variables.get("departmentId.parentId"));
        assertEquals("007", variables.get("departmentId.costCenter"));
        assertEquals(2, variables.get("departmentId.parentId.level"));
    }

    @Test
    void aRuleComparesAPathByTheTypeOfTheFieldItReaches() {
        Filters level = Filters.of(List.of("departmentId.level", ">", 9));
        // As text, "10" sorts before "9"; as the Integer the path reaches, it is the larger.
        assertTrue(FlowGatewayRouter.conditionHolds(MODEL, level, null, Map.of("departmentId.level", "10")));
        assertFalse(FlowGatewayRouter.conditionHolds(MODEL, level, null, Map.of("departmentId.level", "8")));
        assertTrue(FlowGatewayRouter.conditionHolds(MODEL, Filters.of(List.of("departmentId.establishedOn", "<",
                "2021-01-01")), null, Map.of("departmentId.establishedOn", "2020-01-06")));
        // A path with no value is not set.
        assertTrue(FlowGatewayRouter.conditionHolds(MODEL, Filters.of(Arrays.asList("departmentId.costCenter",
                "IS NOT SET", null)), null, Map.of()));
    }

    private static List<String> names(List<MetaField> fields) {
        return fields.stream().map(MetaField::getFieldName).toList();
    }
}
