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
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.relation.RelationDeleteHandler;
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
}
