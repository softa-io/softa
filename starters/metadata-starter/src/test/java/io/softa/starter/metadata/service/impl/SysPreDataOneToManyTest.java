package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SysPreData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-loading a seed file whose rows already exist: the paths that only ever run on the SECOND load of
 * a file, and were therefore the least exercised part of the loader.
 *
 * <p>Driven through {@link SysPreDataServiceImpl#handlePredefinedData} with a parent carrying one
 * OneToMany child, because the defects being pinned here are all about the seam between the two: the
 * TYPE of the id the parent hands its children, the ORDER in which children are reconciled against
 * the file, and whether a freeze on the parent reaches them at all.
 */
class SysPreDataOneToManyTest {

    private static final String PARENT = "TenantOptionSet";
    private static final String CHILD = "TenantOptionItem";
    private static final String CHILD_FK = "optionSetId";
    private static final Long TENANT = 7L;
    /** The parent's row id as sys_pre_data stores it: a String, whatever the model's key type. */
    private static final String STORED_ROW_ID = "871100432280191149";
    private static final Long TYPED_ROW_ID = 871100432280191149L;

    private ModelService<Serializable> modelService;
    private SysPreDataServiceImpl service;

    @BeforeAll
    static void ensureSystemConfig() {
        // Framework exception construction reaches I18n via BaseException, which requires a non-null
        // SystemConfig.env. Raw unit tests must seed it.
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
        // The binding ledger is the one DB hop the reconciliation makes on its own; stubbing it here
        // lets everything above it run for real. Writes to the ledger go the same way.
        doReturn(1L).when(service).createOne(any(SysPreData.class));
        doReturn(true).when(service).updateOne(any(SysPreData.class));
        doReturn(true).when(service).deleteByFilters(any(Filters.class));
    }

    @Test
    void theParentHandsItsChildrenATypedId() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);

        Serializable rowId = withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow())));

        // A String here is the defect: it reaches resolveReferencedPreIds as the child's
        // back-reference and is re-read as a preId, so the load dies claiming the id does not exist.
        assertInstanceOf(Long.class, rowId);
        assertEquals(TYPED_ROW_ID, rowId);
    }

    @Test
    void childrenAreReconciledBeforeTheyAreWritten() {
        // Nothing is bound: the parent and the child are both created, and one stale child row is
        // sitting under the parent. The order is what matters — a renamed child's old row has to go
        // before its replacement is written, or the child model's unique key rejects the write.
        bindings();
        when(modelService.createOne(eq(PARENT), anyMap())).thenReturn(TYPED_ROW_ID);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);
        when(modelService.getIds(eq(CHILD), any(Filters.class))).thenReturn(List.of(999L));

        withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))));

        InOrder inOrder = Mockito.inOrder(modelService, service);
        inOrder.verify(modelService).getIds(eq(CHILD), any(Filters.class));
        inOrder.verify(modelService).deleteByFilters(eq(CHILD), any(Filters.class));
        // The bindings of the rows just deleted go with them, or the next run finds a binding
        // pointing at nothing and dies trying to update it.
        inOrder.verify(service).deleteByFilters(any(Filters.class));
        inOrder.verify(modelService).createOne(eq(CHILD), anyMap());
    }

    @Test
    void aFrozenParentLeavesItsChildrenAlone() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, true));

        Serializable rowId = withMetadata(() ->
                inTenant(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))));

        assertEquals(TYPED_ROW_ID, rowId);
        // A freeze covers the record, not half of it. The destructive half of a re-load is exactly
        // this child sync — it deletes every child the file no longer declares.
        verify(modelService, never()).getIds(eq(CHILD), any(Filters.class));
        verify(modelService, never()).deleteByFilters(eq(CHILD), any(Filters.class));
        verify(modelService, never()).createOne(eq(CHILD), anyMap());
        verify(modelService, never()).updateOne(eq(PARENT), anyMap());
    }

    @Test
    void aBindingWhoseRowIsGoneIsRecreatedWithoutInheritingTheClearedFields() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        // The row the binding names is gone — someone deleted the seeded row through the ordinary UI.
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(false);
        when(modelService.exist(PARENT, TYPED_ROW_ID)).thenReturn(false);
        when(modelService.createOne(eq(PARENT), anyMap())).thenReturn(999L);

        Serializable rowId = withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow())));

        assertEquals(999L, rowId);

        ArgumentCaptor<Map<String, Object>> updated = captor();
        verify(modelService).updateOne(eq(PARENT), updated.capture());
        // The update still clears what the seed no longer says.
        assertEquals(null, updated.getValue().get("description"));

        ArgumentCaptor<Map<String, Object>> created = captor();
        verify(modelService).createOne(eq(PARENT), created.capture());
        // …but the recreate must not inherit those nulls: a default value is only filled when the key
        // is absent, so carrying them over would write null over the model's defaults and produce a
        // row that a first load never would.
        assertFalse(created.getValue().containsKey("description"), created.getValue().toString());
        // A generated-id strategy must not be handed the old id: the row is recreated, not resurrected.
        assertFalse(created.getValue().containsKey("id"), created.getValue().toString());
    }

    // ---- fixtures --------------------------------------------------------

    /** The parent row as the file declares it: the preId under `id`, one column, and its children. */
    private static Map<String, Object> parentRow(Map<String, Object>... children) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "tenant_option_set.OrganizationType");
        row.put("label", "Organization Type");
        if (children.length > 0) {
            row.put("optionItems", List.of(children));
        }
        return row;
    }

    private static Map<String, Object> childRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "tenant_option_item.OrganizationType.Company");
        row.put("itemCode", "Company");
        return row;
    }

    private static SysPreData binding(String model, String preId, String rowId, boolean frozen) {
        SysPreData preData = new SysPreData();
        preData.setModel(model);
        preData.setPreId(preId);
        preData.setRowId(rowId);
        preData.setFrozen(frozen);
        preData.setTenantId(TENANT);
        return preData;
    }

    /** What the binding ledger answers with, for every lookup this load makes. */
    private void bindings(SysPreData... found) {
        doReturn(List.of(found)).when(service).searchList(any(Filters.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Map<String, T>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    /**
     * The model metadata the load reads: each model's tenancy, its fields' types (which is how the
     * OneToMany child list is told apart from the main row), the key type, and the updatable set the
     * update branch nulls the absent members of.
     */
    private static Serializable withMetadata(Supplier<Serializable> action) {
        // Built before the static mock is entered: stubbing a mock inside an unfinished
        // `mm.when(...)` is nested stubbing, which Mockito rejects.
        MetaField parentId = field(FieldType.LONG);
        MetaField parentLabel = field(FieldType.STRING);
        MetaField optionItems = relation(FieldType.ONE_TO_MANY, CHILD, CHILD_FK);
        MetaField childId = field(FieldType.LONG);
        MetaField childItemCode = field(FieldType.STRING);
        MetaField childFk = relation(FieldType.MANY_TO_ONE, PARENT, null);
        MetaModel multiTenant = mock(MetaModel.class);
        when(multiTenant.isMultiTenant()).thenReturn(true);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getModel(PARENT)).thenReturn(multiTenant);
            mm.when(() -> ModelManager.getModel(CHILD)).thenReturn(multiTenant);
            mm.when(() -> ModelManager.isMultiTenantModel(PARENT)).thenReturn(true);
            mm.when(() -> ModelManager.isMultiTenantModel(CHILD)).thenReturn(true);
            mm.when(() -> ModelManager.getModelField(PARENT, "id")).thenReturn(parentId);
            mm.when(() -> ModelManager.getModelField(PARENT, "label")).thenReturn(parentLabel);
            mm.when(() -> ModelManager.getModelField(PARENT, "optionItems")).thenReturn(optionItems);
            mm.when(() -> ModelManager.getModelField(CHILD, "id")).thenReturn(childId);
            mm.when(() -> ModelManager.getModelField(CHILD, "itemCode")).thenReturn(childItemCode);
            mm.when(() -> ModelManager.getModelField(CHILD, CHILD_FK)).thenReturn(childFk);
            mm.when(() -> ModelManager.getIdStrategy(PARENT)).thenReturn(IdStrategy.DISTRIBUTED_LONG);
            mm.when(() -> ModelManager.getIdStrategy(CHILD)).thenReturn(IdStrategy.DISTRIBUTED_LONG);
            // Mutable: the update branch removes the seeded keys from it in place.
            mm.when(() -> ModelManager.getModelUpdatableFieldsWithoutXToMany(PARENT))
                    .thenReturn(new HashSet<>(Set.of("label", "description")));
            mm.when(() -> ModelManager.getModelUpdatableFieldsWithoutXToMany(CHILD))
                    .thenReturn(new HashSet<>(Set.of("itemCode", "label")));
            return action.get();
        }
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

    private static Serializable inTenant(Supplier<Serializable> action) {
        Context context = ContextHolder.cloneContext();
        context.setTenantId(TENANT);
        AtomicReference<Serializable> result = new AtomicReference<>();
        ContextHolder.runWith(context, () -> result.set(action.get()));
        return result.get();
    }

}
