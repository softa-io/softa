package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.entity.TimelineSlice;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.relation.RelationDeleteHandler;
import io.softa.framework.orm.service.versioning.IdentityStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelServiceImplTest {

    @Test
    void createOrUpdateUsesTupleFilterToSplitRows() {
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.getModelField("SysField", "modelName"))
                    .thenReturn(createMetaField("SysField", "modelName", FieldType.STRING));
            modelManager.when(() -> ModelManager.getModelField("SysField", "fieldName"))
                    .thenReturn(createMetaField("SysField", "fieldName", FieldType.STRING));

            ModelServiceImpl<Long> modelService = Mockito.spy(new ModelServiceImpl<>());
            doReturn(List.of(new HashMap<>(Map.of(
                    ModelConstant.ID, 10L,
                    "modelName", "User",
                    "fieldName", "name"
            )))).when(modelService).searchList(eq("SysField"), any(FlexQuery.class));
            doReturn(Boolean.TRUE).when(modelService).updateList(eq("SysField"), anyList());
            doReturn(List.of(20L)).when(modelService).createList(eq("SysField"), anyList());

            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(new HashMap<>(Map.of("modelName", "User", "fieldName", "name", "label", "Name")));
            rows.add(new HashMap<>(Map.of("modelName", "Order", "fieldName", "status", "label", "Status")));

            modelService.createOrUpdate("SysField", rows, List.of("modelName", "fieldName"));

            ArgumentCaptor<FlexQuery> flexQueryCaptor = ArgumentCaptor.forClass(FlexQuery.class);
            verify(modelService).searchList(eq("SysField"), flexQueryCaptor.capture());
            Assertions.assertEquals(
                    "[\"modelName,fieldName\",\"IN\",[[\"User\",\"name\"],[\"Order\",\"status\"]]]",
                    flexQueryCaptor.getValue().getFilters().toString()
            );

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> updateCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelService).updateList(eq("SysField"), updateCaptor.capture());
            Assertions.assertEquals(1, updateCaptor.getValue().size());
            Assertions.assertEquals(10L, updateCaptor.getValue().getFirst().get(ModelConstant.ID));
            Assertions.assertEquals("User", updateCaptor.getValue().getFirst().get("modelName"));
            Assertions.assertEquals("name", updateCaptor.getValue().getFirst().get("fieldName"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> createCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelService).createList(eq("SysField"), createCaptor.capture());
            Assertions.assertEquals(1, createCaptor.getValue().size());
            Assertions.assertFalse(createCaptor.getValue().getFirst().containsKey(ModelConstant.ID));
            Assertions.assertEquals("Order", createCaptor.getValue().getFirst().get("modelName"));
            Assertions.assertEquals("status", createCaptor.getValue().getFirst().get("fieldName"));
        }
    }

    private MetaField createMetaField(String modelName, String fieldName, FieldType fieldType) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", modelName);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        return metaField;
    }

    // ------------------------------------------------------------------ versioning read scope
    // Every public read path must route through the VersioningStrategy seam (the timeline
    // effective-date clamp; a no-op for regular models). One assertion per public read method;
    // the getById family funnels through searchList and is covered transitively.

    private static final String SCOPED_MODEL = "DeptInfo";

    private record ReadFixture(ModelServiceImpl<Serializable> service, JdbcService<Serializable> jdbc,
                               PermissionService permission, VersioningStrategy strategy, Filters scoped) {}

    @SuppressWarnings("unchecked")
    private ReadFixture readFixture() {
        ModelServiceImpl<Serializable> service = new ModelServiceImpl<>();
        JdbcService<Serializable> jdbc = Mockito.mock(JdbcService.class);
        PermissionService permission = Mockito.mock(PermissionService.class);
        VersioningStrategyResolver versioning = Mockito.mock(VersioningStrategyResolver.class);
        VersioningStrategy strategy = Mockito.mock(VersioningStrategy.class);
        ReflectionTestUtils.setField(service, "jdbcService", jdbc);
        ReflectionTestUtils.setField(service, "permissionService", permission);
        ReflectionTestUtils.setField(service, "versioning", versioning);
        when(versioning.of(SCOPED_MODEL)).thenReturn(strategy);
        Filters scoped = new Filters().eq("scopedBySeam", true);
        when(strategy.scopeRead(eq(SCOPED_MODEL), any(Filters.class))).thenReturn(scoped);
        when(strategy.scopeRead(eq(SCOPED_MODEL), any(FlexQuery.class))).thenReturn(scoped);
        when(permission.appendScopeAccessFilters(eq(SCOPED_MODEL), any())).thenAnswer(inv -> inv.getArgument(1));
        when(permission.filterReadableFields(eq(SCOPED_MODEL), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        return new ReadFixture(service, jdbc, permission, strategy, scoped);
    }

    private FlexQuery capturedIdQuery(ReadFixture fixture, String fieldName) {
        ArgumentCaptor<FlexQuery> captor = ArgumentCaptor.forClass(FlexQuery.class);
        verify(fixture.jdbc()).getIds(eq(SCOPED_MODEL), eq(fieldName), captor.capture());
        return captor.getValue();
    }

    @Test
    void getIdsRoutesThroughVersioningReadScope() {
        ReadFixture fixture = readFixture();
        when(fixture.jdbc().getIds(eq(SCOPED_MODEL), eq(ModelConstant.ID), any(FlexQuery.class)))
                .thenReturn(List.of());
        Filters original = new Filters().eq("name", "x");

        fixture.service().getIds(SCOPED_MODEL, original);

        verify(fixture.strategy()).scopeRead(SCOPED_MODEL, original);
        Assertions.assertSame(fixture.scoped(), capturedIdQuery(fixture, ModelConstant.ID).getFilters());
    }

    @Test
    void getIdsWithLimitRoutesThroughVersioningReadScope() {
        ReadFixture fixture = readFixture();
        when(fixture.jdbc().getIds(eq(SCOPED_MODEL), eq(ModelConstant.ID), any(FlexQuery.class)))
                .thenReturn(List.of());
        Filters original = new Filters().eq("name", "x");

        fixture.service().getIds(SCOPED_MODEL, original, 7);

        verify(fixture.strategy()).scopeRead(SCOPED_MODEL, original);
        FlexQuery query = capturedIdQuery(fixture, ModelConstant.ID);
        Assertions.assertSame(fixture.scoped(), query.getFilters());
        Assertions.assertEquals(7, query.getLimitSize());
    }

    @Test
    void getRelatedIdsRoutesThroughVersioningReadScope() {
        ReadFixture fixture = readFixture();
        when(fixture.jdbc().getIds(eq(SCOPED_MODEL), eq("deptId"), any(FlexQuery.class)))
                .thenReturn(List.of());
        Filters original = new Filters().eq("name", "x");

        fixture.service().getRelatedIds(SCOPED_MODEL, original, "deptId");

        verify(fixture.strategy()).scopeRead(SCOPED_MODEL, original);
        FlexQuery query = capturedIdQuery(fixture, "deptId");
        Assertions.assertSame(fixture.scoped(), query.getFilters());
        Assertions.assertTrue(query.isDistinct());
    }

    @Test
    void countRoutesThroughVersioningReadScope() {
        ReadFixture fixture = readFixture();
        when(fixture.jdbc().count(eq(SCOPED_MODEL), any(FlexQuery.class))).thenReturn(0L);
        Filters original = new Filters().eq("name", "x");

        fixture.service().count(SCOPED_MODEL, original);

        verify(fixture.strategy()).scopeRead(SCOPED_MODEL, original);
        ArgumentCaptor<FlexQuery> captor = ArgumentCaptor.forClass(FlexQuery.class);
        verify(fixture.jdbc()).count(eq(SCOPED_MODEL), captor.capture());
        Assertions.assertSame(fixture.scoped(), captor.getValue().getFilters());
    }

    @Test
    void searchListRoutesThroughVersioningReadScope() {
        ReadFixture fixture = readFixture();
        when(fixture.jdbc().selectByFilter(eq(SCOPED_MODEL), any(FlexQuery.class))).thenReturn(List.of());
        FlexQuery flexQuery = new FlexQuery(new Filters().eq("name", "x"));

        fixture.service().searchList(SCOPED_MODEL, flexQuery);

        verify(fixture.strategy()).scopeRead(SCOPED_MODEL, flexQuery);
        Assertions.assertSame(fixture.scoped(), flexQuery.getFilters());
        verify(fixture.jdbc()).selectByFilter(SCOPED_MODEL, flexQuery);
    }

    @Test
    void searchPageRoutesThroughVersioningReadScope() {
        ReadFixture fixture = readFixture();
        Page<Map<String, Object>> page = Page.of(1, 10);
        when(fixture.jdbc().selectByPage(eq(SCOPED_MODEL), any(FlexQuery.class), eq(page))).thenReturn(page);
        FlexQuery flexQuery = new FlexQuery(new Filters().eq("name", "x"));

        fixture.service().searchPage(SCOPED_MODEL, flexQuery, page);

        verify(fixture.strategy()).scopeRead(SCOPED_MODEL, flexQuery);
        Assertions.assertSame(fixture.scoped(), flexQuery.getFilters());
        verify(fixture.jdbc()).selectByPage(SCOPED_MODEL, flexQuery, page);
    }

    @Test
    void deleteBySliceIdDeletesOneVersionWithoutTriggeringEntityDeleteHooks() {
        ReadFixture fixture = readFixture();
        RelationDeleteHandler handler = Mockito.mock(RelationDeleteHandler.class);
        ReflectionTestUtils.setField(fixture.service(), "relationDeleteHandler", handler);
        TimelineSlice slice = new TimelineSlice();
        slice.setId(1L);
        slice.setSliceId(11L);
        slice.setEffectiveStartDate(LocalDate.of(2025, 1, 1));
        slice.setEffectiveEndDate(LocalDate.of(2025, 12, 31));
        when(fixture.strategy().versionSlice(SCOPED_MODEL, 11L)).thenReturn(slice);
        when(fixture.strategy().deleteVersion(SCOPED_MODEL, slice)).thenReturn(true);

        boolean deleted = fixture.service().deleteBySliceId(SCOPED_MODEL, 11L);

        Assertions.assertTrue(deleted);
        // Permission runs against the slice's owning logical id, before the delete.
        verify(fixture.permission()).checkIdsAccess(SCOPED_MODEL, List.of(1L), AccessType.DELETE);
        // The entity survives: inbound-FK delete hooks and the physical entity delete never run.
        verifyNoInteractions(handler);
        verify(fixture.jdbc(), never()).deleteByIds(any(), anyList(), anyList());
    }

    // ------------------------------------------------------------------ addVersion

    @Test
    void addVersionDelegatesToCreateAndReturnsTheSliceId() {
        ModelServiceImpl<Serializable> service = Mockito.spy(new ModelServiceImpl<>());
        VersioningStrategyResolver versioning = Mockito.mock(VersioningStrategyResolver.class);
        VersioningStrategy strategy = Mockito.mock(VersioningStrategy.class);
        ReflectionTestUtils.setField(service, "versioning", versioning);
        when(versioning.of(SCOPED_MODEL)).thenReturn(strategy);
        Map<String, Object> row = new HashMap<>(Map.of(ModelConstant.ID, 6L, "name", "R&D 2"));
        Mockito.doAnswer(inv -> {
            // The create pipeline mints the slice key into the row.
            List<Map<String, Object>> rows = inv.getArgument(1);
            rows.getFirst().put(ModelConstant.SLICE_ID, 42L);
            return List.of(6L);
        }).when(service).createList(eq(SCOPED_MODEL), anyList());

        java.io.Serializable sliceId = service.addVersion(SCOPED_MODEL, row);

        Assertions.assertEquals(42L, sliceId);
        verify(strategy).checkVersionCreate(SCOPED_MODEL, row);
        verify(service).createList(eq(SCOPED_MODEL), anyList());
    }

    @Test
    void addVersionOnNonTimelineModelIsRejected() {
        ModelServiceImpl<Serializable> service = new ModelServiceImpl<>();
        VersioningStrategyResolver versioning = Mockito.mock(VersioningStrategyResolver.class);
        ReflectionTestUtils.setField(service, "versioning", versioning);
        when(versioning.of(SCOPED_MODEL)).thenReturn(new IdentityStrategy<>(null));

        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> service.addVersion(SCOPED_MODEL, new HashMap<>(Map.of("name", "x"))));
        Assertions.assertTrue(e.getMessage().contains("not a timeline model"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addVersionAndFetchReadsTheVersionRowAcrossTimeline() {
        ModelServiceImpl<Serializable> service = Mockito.spy(new ModelServiceImpl<>());
        JdbcService<Serializable> jdbc = Mockito.mock(JdbcService.class);
        PermissionService permission = Mockito.mock(PermissionService.class);
        ReflectionTestUtils.setField(service, "jdbcService", jdbc);
        ReflectionTestUtils.setField(service, "permissionService", permission);
        when(permission.filterReadableFields(eq(SCOPED_MODEL), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        Map<String, Object> versionRow = new HashMap<>(Map.of(ModelConstant.SLICE_ID, 42L, "name", "R&D 2"));
        when(jdbc.selectByFilter(eq(SCOPED_MODEL), any(FlexQuery.class))).thenReturn(List.of(versionRow));
        Mockito.doReturn(42L).when(service).addVersion(eq(SCOPED_MODEL), anyMap());

        Map<String, Object> fetched = service.addVersionAndFetch(SCOPED_MODEL, new HashMap<>(), ConvertType.TYPE_CAST);

        Assertions.assertEquals("R&D 2", fetched.get("name"));
        // The fetch is keyed by the new sliceId and must NOT apply the as-of clamp
        // (the new version's effective date may not be today).
        ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
        verify(jdbc).selectByFilter(eq(SCOPED_MODEL), query.capture());
        Assertions.assertTrue(query.getValue().isAcrossTimeline());
        Assertions.assertTrue(String.valueOf(query.getValue().getFilters()).contains(ModelConstant.SLICE_ID));
    }

    // ------------------------------------------------------------------ copy semantics
    // A copy of a timeline model must become a NEW entity: the copyable set excludes every
    // timeline structural key, so the row handed to create carries no id/sliceId/effective
    // dates — createSlices (via the seam) then mints a fresh logical id + a genesis slice,
    // instead of grafting a slice onto the source entity's own timeline.

    @Test
    @SuppressWarnings("unchecked")
    void copyByIdsOnTimelineModelStripsStructuralKeysSoTheCopyIsANewEntity() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isCopyableModel(SCOPED_MODEL)).thenReturn(true);
            mm.when(() -> ModelManager.getModelCopyableFields(SCOPED_MODEL))
                    .thenReturn(List.of("name", "description"));

            ModelServiceImpl<Long> service = Mockito.spy(new ModelServiceImpl<>());
            // The source read is the as-of slice, carrying identity + structural + business fields.
            Map<String, Object> sourceSlice = new HashMap<>();
            sourceSlice.put(ModelConstant.ID, 6L);
            sourceSlice.put(ModelConstant.SLICE_ID, 3L);
            sourceSlice.put(ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2022, 9, 1));
            sourceSlice.put(ModelConstant.EFFECTIVE_END_DATE, LocalDate.of(9999, 12, 31));
            sourceSlice.put("code", "D001");
            sourceSlice.put("name", "R&D");
            sourceSlice.put("description", "dept");
            doReturn(new ArrayList<>(List.of(sourceSlice)))
                    .when(service).getByIds(eq(SCOPED_MODEL), anyList(), isNull());
            doReturn(List.of(99L)).when(service).createList(eq(SCOPED_MODEL), anyList());

            service.copyByIds(SCOPED_MODEL, List.of(6L));

            ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
            verify(service).createList(eq(SCOPED_MODEL), captor.capture());
            Map<String, Object> copied = captor.getValue().getFirst();
            Assertions.assertFalse(copied.containsKey(ModelConstant.ID), "must not carry the source logical id");
            Assertions.assertFalse(copied.containsKey(ModelConstant.SLICE_ID));
            Assertions.assertFalse(copied.containsKey(ModelConstant.EFFECTIVE_START_DATE));
            Assertions.assertFalse(copied.containsKey(ModelConstant.EFFECTIVE_END_DATE));
            Assertions.assertFalse(copied.containsKey("code"), "businessKey is copyable=false");
            Assertions.assertEquals("R&D", copied.get("name"), "business fields are carried");
            Assertions.assertEquals("dept", copied.get("description"));
        }
    }
}
