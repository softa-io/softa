package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.seed.SeedLevel;
import io.softa.starter.metadata.seed.SeedPushScope;
import io.softa.starter.metadata.seed.TenantSeedFileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bringing a tenant's copy of a tenant seed file up to date: only what the tenant never got is written, and
 * only a declared push reaches into what it has.
 */
class SysPreDataApplyNewRowsTest {

    private static final Long TENANT = 7L;

    private ModelService<Serializable> modelService;
    private SysPreDataServiceImpl service;
    /** The tenant's bindings, as the ledger would answer for them. */
    private final List<SysPreData> ledger = new ArrayList<>();
    /** Bindings the load wrote. */
    private final List<SysPreData> written = new ArrayList<>();

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        SystemConfig.env.setEnableMultiTenancy(true);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        service = spy(new SysPreDataServiceImpl(modelService));
        // The ledger answers a lookup with the bindings whose preId the filter names.
        doAnswer(call -> {
            String filter = call.getArgument(0).toString();
            return ledger.stream().filter(b -> filter.contains("\"" + b.getPreId() + "\"")
                    && filter.contains("\"" + b.getModel() + "\"")).toList();
        }).when(service).searchList(any(Filters.class));
        doAnswer(call -> {
            written.add(call.getArgument(0));
            return 1L;
        }).when(service).createOne(any(SysPreData.class));
        doReturn(true).when(service).updateOne(any(SysPreData.class));
        doReturn(true).when(service).deleteByFilters(any(Filters.class));
    }

    @Test
    void onlyRowsTheTenantNeverGotAreWritten() {
        bind("Widget", "widget.bound", "55");
        when(modelService.exist("Widget", 55L)).thenReturn(true);
        when(modelService.getIds(eq("Widget"), argThat(f -> f.toString().contains("\"C\"")))).thenReturn(List.of(77L));
        when(modelService.getIds(eq("Widget"), argThat(f -> f.toString().contains("\"N\"")))).thenReturn(List.of());
        when(modelService.createOne(eq("Widget"), anyMap())).thenReturn(88L);
        when(modelService.createOne(eq("Gadget"), anyMap())).thenReturn(99L);

        TenantSeedFileResult result = apply("SeedApplyTest.Rows.json", SeedPushScope.NONE,
                Set.of("Widget/widget.bound", "Widget/widget.claim", "Widget/widget.new", "Gadget/gadget.orphan"));

        // widget.bound: bound, left alone. widget.claim: the tenant already has code C — adopted, not
        // written. widget.new: created. gadget.orphan: created, its widget is still there.
        assertEquals(2, result.created());
        assertEquals(1, result.claimed());
        verify(modelService, never()).updateOne(eq("Widget"), anyMap());
        ArgumentCaptor<Map<String, Object>> created = captor();
        verify(modelService).createOne(eq("Widget"), created.capture());
        assertEquals("N", created.getValue().get("code"));
        assertEquals("77", bindingOf("widget.claim").getRowId());
        assertEquals("88", bindingOf("widget.new").getRowId());
        // Every binding written says which file brought its row.
        written.forEach(b -> assertEquals("SeedApplyTest.Rows.json", b.getSourceFile()));
    }

    @Test
    void aRowTheReleaseDidNotAddIsNotFilledIn() {
        // The tenant lacks widget.new, but this release added only widget.claim: a gap from before is left.
        when(modelService.getIds(eq("Widget"), any(Filters.class))).thenReturn(List.of());
        when(modelService.createOne(eq("Widget"), anyMap())).thenReturn(88L);

        TenantSeedFileResult result = apply("SeedApplyTest.Rows.json", SeedPushScope.NONE, Set.of("Widget/widget.claim"));

        assertEquals(1, result.created());
        verify(modelService).createOne(eq("Widget"), argThat(row -> "C".equals(row.get("code"))));
        verify(modelService, never()).createOne(eq("Widget"), argThat(row -> "N".equals(row.get("code"))));
        verify(modelService, never()).createOne(eq("Gadget"), anyMap());
    }

    @Test
    void aReleaseThatAddedNothingDoesNotReadTheFile() {
        TenantSeedFileResult result = apply("SeedApplyTest.Rows.json", SeedPushScope.NONE, Set.of());

        assertEquals(0, result.created() + result.claimed() + result.skipped());
        verify(modelService, never()).createOne(any(String.class), anyMap());
    }

    @Test
    void aRowTheTenantCannotTakeIsNamedInTheFailure() {
        when(modelService.getIds(eq("Widget"), any(Filters.class))).thenReturn(List.of());
        when(modelService.createOne(eq("Widget"), anyMap())).thenThrow(
                new DuplicateKeyException("duplicate key value violates unique constraint"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> apply("SeedApplyTest.Rows.json", SeedPushScope.NONE, Set.of("Widget/widget.new")));

        assertTrue(failure.getMessage().contains("widget.new"), failure.getMessage());
        assertTrue(failure.getMessage().contains("same unique key"), failure.getMessage());
    }

    @Test
    void aRowUnderASeedRowTheTenantDeletedIsNotCreated() {
        bind("Widget", "widget.bound", "55");
        when(modelService.exist("Widget", 55L)).thenReturn(false);
        when(modelService.getIds(eq("Widget"), any(Filters.class))).thenReturn(List.of(77L));

        TenantSeedFileResult result = apply("SeedApplyTest.Rows.json", SeedPushScope.NONE,
                Set.of("Gadget/gadget.orphan"));

        verify(modelService, never()).createOne(eq("Gadget"), anyMap());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("gadget.orphan") && n.contains("Widget:widget.bound")),
                result.notes().toString());
        // The deleted widget itself is not recreated either: its binding stands for the tenant's decision.
        verify(modelService, never()).createOne(eq("Widget"), argThat(row -> "B".equals(row.get("code"))));
    }

    @Test
    void newItemsUnderAnExistingRowArePushedWithoutRemovingAnything() {
        bind("Box", "box.x", "10");
        bind("Item", "item.old", "20");
        when(modelService.exist("Box", 10L)).thenReturn(true);
        when(modelService.getIds(eq("Item"), argThat(f -> f.toString().contains("\"c\"")))).thenReturn(List.of(21L));
        when(modelService.getIds(eq("Item"), argThat(f -> f.toString().contains("\"n\"")))).thenReturn(List.of());
        when(modelService.createOne(eq("Item"), anyMap())).thenReturn(22L);

        // The release added two items under the existing box.
        TenantSeedFileResult result = apply("SeedApplyTest.Nested.json", SeedPushScope.NEW_NESTED_ITEMS,
                Set.of("Item/item.claim", "Item/item.new"));

        assertEquals(1, result.pushed());
        assertEquals(1, result.claimed());
        ArgumentCaptor<Map<String, Object>> created = captor();
        verify(modelService).createOne(eq("Item"), created.capture());
        assertEquals("n", created.getValue().get("itemCode"));
        assertEquals(10L, created.getValue().get("boxId"));
        // Unlike a load, the box's other items — the tenant's own among them — are not reconciled away.
        verify(modelService, never()).deleteByFilters(eq("Item"), any(Filters.class));
        verify(modelService, never()).updateOne(eq("Box"), anyMap());
    }

    @Test
    void aNewRowCountsTheRowsNestedInIt() {
        // The tenant never got the box: it is created with its three items, and all four count as created.
        when(modelService.getIds(any(String.class), any(Filters.class))).thenReturn(List.of());
        when(modelService.createOne(eq("Box"), anyMap())).thenReturn(10L);
        when(modelService.createList(eq("Item"), anyList())).thenReturn(List.of(21L, 22L, 23L));
        when(modelService.createOne(eq("Item"), anyMap())).thenReturn(21L, 22L, 23L);

        TenantSeedFileResult result = apply("SeedApplyTest.Nested.json", SeedPushScope.NONE,
                Set.of("Box/box.x", "Item/item.old", "Item/item.claim", "Item/item.new"));

        assertEquals(4, result.created());
    }

    @Test
    void loadingFilesWholeCountsEveryRowAndTheRowsNestedInThem() {
        // Three widgets and a gadget; a box with its three items.
        int rows = withMetadata(() -> service.rowCountOf(SeedLevel.TENANT.getDataDir(),
                List.of("SeedApplyTest.Rows.json", "SeedApplyTest.Nested.json")));

        assertEquals(8, rows);
    }

    @Test
    void withoutThePushExistingRowsGetNoNewItems() {
        bind("Box", "box.x", "10");

        apply("SeedApplyTest.Nested.json", SeedPushScope.NONE, Set.of("Item/item.claim", "Item/item.new"));

        verify(modelService, never()).createOne(eq("Item"), anyMap());
    }

    @Test
    void aDroppedColumnIsRemovedOnlyWhenItsFieldIsGone() {
        bind("ImportTemplate", "tpl.widget", "5");
        bind("ImportTemplateField", "col.keep", "30");
        bind("ImportTemplateField", "col.gone", "31");
        bind("ImportTemplateField", "col.moved", "32");
        when(modelService.searchList(eq("ImportTemplateField"), any(FlexQuery.class))).thenAnswer(call -> {
            String filter = ((FlexQuery) call.getArgument(1)).getFilters().toString();
            if (filter.contains("31")) {
                return List.of(Map.of("id", 31L, "fieldName", "removedField.name", "templateId", 5L));
            }
            return List.of(Map.of("id", 32L, "fieldName", "label", "templateId", 5L));
        });
        when(modelService.searchList(eq("ImportTemplate"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 5L, "modelName", "Widget")));

        // The release dropped two columns from the file.
        TenantSeedFileResult result = withMetadata(() -> inTenant(() -> service.applyNewRows(
                "ImportTemplate.SeedApplyTest.json", SeedPushScope.INVALID_COLUMNS, Set.of(),
                Set.of("ImportTemplateField/col.gone", "ImportTemplateField/col.moved"))));

        assertEquals(1, result.removed());
        verify(modelService).deleteById("ImportTemplateField", 31L);
        // `label` still exists on Widget: the column only left the default template, and still works.
        verify(modelService, never()).deleteById("ImportTemplateField", 32L);
    }

    @Test
    void theRowKeysOfAFileIncludeItsNestedRows() {
        Set<String> keys = withMetadata(() -> service.rowKeysOf("data-tenant/", "SeedApplyTest.Nested.json"));

        assertEquals(List.of("Box/box.x", "Item/item.old", "Item/item.claim", "Item/item.new"), List.copyOf(keys));
    }

    // ---- fixtures --------------------------------------------------------

    private SysPreData bind(String model, String preId, String rowId) {
        SysPreData binding = new SysPreData();
        binding.setModel(model);
        binding.setPreId(preId);
        binding.setRowId(rowId);
        binding.setFrozen(false);
        binding.setTenantId(TENANT);
        ledger.add(binding);
        return binding;
    }

    private SysPreData bindingOf(String preId) {
        return written.stream().filter(b -> preId.equals(b.getPreId())).findFirst()
                .orElseThrow(() -> new AssertionError("no binding written for " + preId + ": " + written));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private TenantSeedFileResult apply(String file, SeedPushScope push, Set<String> added) {
        return withMetadata(() -> inTenant(() -> service.applyNewRows(file, push, added, Set.of())));
    }

    /**
     * Widget (business key: code) and Gadget under it; Box with its Items (business key: boxCode + itemCode);
     * the import template pair. All multi-tenant.
     */
    private static <T> T withMetadata(Supplier<T> action) {
        MetaModel widget = model(List.of("code"));
        MetaModel noKey = model(List.of());
        MetaModel item = model(List.of("boxCode", "itemCode"));
        MetaField id = field(FieldType.LONG);
        MetaField text = field(FieldType.STRING);
        MetaField gadgetWidget = relation(FieldType.MANY_TO_ONE, "Widget", null);
        MetaField boxItems = relation(FieldType.ONE_TO_MANY, "Item", "boxId");
        MetaField itemBox = relation(FieldType.MANY_TO_ONE, "Box", null);
        MetaField columnTemplate = relation(FieldType.MANY_TO_ONE, "ImportTemplate", null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            for (String m : List.of("Widget", "Gadget", "Box", "Item", "ImportTemplate", "ImportTemplateField")) {
                mm.when(() -> ModelManager.isMultiTenantModel(m)).thenReturn(true);
                mm.when(() -> ModelManager.existModel(m)).thenReturn(true);
                mm.when(() -> ModelManager.getIdStrategy(m)).thenReturn(IdStrategy.DISTRIBUTED_LONG);
                mm.when(() -> ModelManager.getModelField(m, "id")).thenReturn(id);
            }
            mm.when(() -> ModelManager.getModel("Widget")).thenReturn(widget);
            mm.when(() -> ModelManager.getModel("Gadget")).thenReturn(noKey);
            mm.when(() -> ModelManager.getModel("Box")).thenReturn(noKey);
            mm.when(() -> ModelManager.getModel("Item")).thenReturn(item);
            mm.when(() -> ModelManager.getModel("ImportTemplate")).thenReturn(noKey);
            mm.when(() -> ModelManager.getModel("ImportTemplateField")).thenReturn(noKey);
            for (String f : List.of("code", "label")) {
                mm.when(() -> ModelManager.getModelField("Widget", f)).thenReturn(text);
            }
            mm.when(() -> ModelManager.getModelField("Gadget", "name")).thenReturn(text);
            mm.when(() -> ModelManager.getModelField("Gadget", "widgetId")).thenReturn(gadgetWidget);
            mm.when(() -> ModelManager.getModelField("Box", "code")).thenReturn(text);
            mm.when(() -> ModelManager.getModelField("Box", "items")).thenReturn(boxItems);
            for (String f : List.of("boxCode", "itemCode", "label")) {
                mm.when(() -> ModelManager.getModelField("Item", f)).thenReturn(text);
            }
            mm.when(() -> ModelManager.getModelField("Item", "boxId")).thenReturn(itemBox);
            mm.when(() -> ModelManager.getModelField("ImportTemplate", "name")).thenReturn(text);
            mm.when(() -> ModelManager.getModelField("ImportTemplate", "modelName")).thenReturn(text);
            mm.when(() -> ModelManager.getModelField("ImportTemplateField", "templateId")).thenReturn(columnTemplate);
            mm.when(() -> ModelManager.getModelField("ImportTemplateField", "fieldName")).thenReturn(text);
            mm.when(() -> ModelManager.existField("Widget", "code")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Widget", "label")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Widget", "removedField")).thenReturn(false);
            return action.get();
        }
    }

    private static MetaModel model(List<String> businessKey) {
        MetaModel model = mock(MetaModel.class);
        when(model.isMultiTenant()).thenReturn(true);
        when(model.getBusinessKey()).thenReturn(businessKey);
        return model;
    }

    private static MetaField field(FieldType fieldType) {
        MetaField metaField = mock(MetaField.class);
        when(metaField.getFieldType()).thenReturn(fieldType);
        return metaField;
    }

    private static MetaField relation(FieldType fieldType, String relatedModel, String relatedField) {
        MetaField metaField = field(fieldType);
        when(metaField.getRelatedModel()).thenReturn(relatedModel);
        when(metaField.getRelatedField()).thenReturn(relatedField);
        return metaField;
    }

    private static <T> T inTenant(Supplier<T> action) {
        Context context = ContextHolder.cloneContext();
        context.setTenantId(TENANT);
        AtomicReference<T> result = new AtomicReference<>();
        ContextHolder.runWith(context, () -> result.set(action.get()));
        return result.get();
    }
}
