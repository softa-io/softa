package io.softa.starter.seed.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
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
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.seed.entity.SysPreData;
import io.softa.starter.seed.service.LoadMode;
import io.softa.starter.seed.service.LoadedRows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static boolean previousMultiTenancy;

    @BeforeAll
    static void ensureSystemConfig() {
        // Framework exception construction reaches I18n via BaseException, which requires a non-null
        // SystemConfig.env. Raw unit tests must seed it.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        // Restored afterwards: the flag is global and the surefire JVM is shared with tests that
        // read it, so leaving it flipped would make their outcome depend on class ordering.
        previousMultiTenancy = SystemConfig.env.isEnableMultiTenancy();
        SystemConfig.env.setEnableMultiTenancy(true);
    }

    @AfterAll
    static void restoreSystemConfig() {
        SystemConfig.env.setEnableMultiTenancy(previousMultiTenancy);
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
    void aSeededChildTheFileNoLongerDeclaresIsDeletedBeforeTheWrite() {
        // The parent is bound; under it sits a seeded child the file no longer declares (999, bound under an
        // old preId). The order is what matters — a renamed child's old row has to go before its replacement
        // is written, or the child model's unique key rejects the write.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false),
                binding(CHILD, "tenant_option_item.OrganizationType.Old", "999", false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);
        storedChildren(true, 999L);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);

        withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))));

        InOrder inOrder = Mockito.inOrder(modelService, service);
        inOrder.verify(modelService).searchList(eq(CHILD), any(FlexQuery.class));
        inOrder.verify(modelService).deleteByIds(CHILD, List.of(999L));
        // The bindings of the rows just deleted go with them, or the next run finds a binding
        // pointing at nothing and dies trying to update it.
        inOrder.verify(service).deleteByFilters(any(Filters.class));
        inOrder.verify(modelService).createOne(eq(CHILD), anyMap());
    }

    @Test
    void aRowTheLoadChangedIsReportedAsWrittenTo() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);

        LoadedRows written = SysPreDataServiceImpl.reportingWrites(() ->
                withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow()))));

        // How a listener learns what a reapply rewrote — a flow design to publish again, say.
        assertEquals(Map.of(PARENT, List.of(STORED_ROW_ID)), written.updated());
        assertEquals(Map.of(), written.created());
    }

    @Test
    void aRowTheLoadLeftAsItWasIsNotReported() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        // The update changed nothing, and the row is there.
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(false);
        storedRow(PARENT, TYPED_ROW_ID, true);

        LoadedRows written = SysPreDataServiceImpl.reportingWrites(() ->
                withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow()))));

        assertTrue(written.isEmpty(), written.toString());
    }

    @Test
    void aChildAddedByHandSurvivesAnOverwrite() {
        // 999 sits under the seeded parent with no binding: someone added it on the page. The file never said
        // anything about it, so overwriting the file leaves it alone.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);
        storedChildren(true, 999L);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);

        withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))));

        verify(modelService, never()).deleteByIds(eq(CHILD), anyList());
        verify(modelService).createOne(eq(CHILD), anyMap());
    }

    @Test
    void aSeededChildDeactivatedOnThePageIsDeletedLikeAnyOther() {
        // 999 is the seed's and the file no longer declares it; someone also deactivated it. Left behind, it
        // would keep its unique key against the row the file declares in its place.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false),
                binding(CHILD, "tenant_option_item.OrganizationType.Old", "999", false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);
        storedChildren(false, 999L);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);

        withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))));

        verify(modelService).deleteByIds(CHILD, List.of(999L));
    }

    @Test
    void anAdditiveLoadWritesOnlyWhatTheFileNames() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);

        withMetadata(() -> inTenant(() -> additive(() -> service.handlePredefinedData(PARENT, parentRow()))));

        ArgumentCaptor<Map<String, Object>> updated = captor();
        verify(modelService).updateOne(eq(PARENT), updated.capture());
        assertEquals("Organization Type", updated.getValue().get("label"));
        // What the tenant set on a field the file does not name is the tenant's.
        assertFalse(updated.getValue().containsKey("description"), updated.getValue().toString());
    }

    @Test
    void anAdditiveLoadDeletesNoChild() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false),
                binding(CHILD, "tenant_option_item.OrganizationType.Old", "999", false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(true);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);

        withMetadata(() -> inTenant(() -> additive(() -> service.handlePredefinedData(PARENT, parentRow(childRow())))));

        verify(modelService, never()).searchList(eq(CHILD), any(FlexQuery.class));
        verify(modelService, never()).deleteByIds(eq(CHILD), anyList());
        verify(modelService, never()).deleteByFilters(eq(CHILD), any(Filters.class));
        verify(modelService).createOne(eq(CHILD), anyMap());
    }

    @Test
    void anAdditiveLoadLeavesADeletedRowDeleted() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        // The tenant deleted the seeded row.
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(false);
        when(modelService.searchList(eq(PARENT), any(FlexQuery.class))).thenReturn(List.of());

        Serializable rowId = withMetadata(() ->
                inTenant(() -> additive(() -> service.handlePredefinedData(PARENT, parentRow(childRow())))));

        assertNull(rowId);
        verify(modelService, never()).createOne(eq(PARENT), anyMap());
        // Nor are the rows nested in it created: they would hang off nothing.
        verify(modelService, never()).createOne(eq(CHILD), anyMap());
    }

    @Test
    void anAdditiveLoadLeavesOutANewRowReferringToADeletedRow() {
        // The parent is bound, but the tenant deleted it; a new child names it by preId.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        when(modelService.searchList(eq(PARENT), any(FlexQuery.class))).thenReturn(List.of());
        Map<String, Object> child = new LinkedHashMap<>(childRow());
        child.put(CHILD_FK, "tenant_option_set.OrganizationType");

        Serializable rowId = withMetadata(() -> inTenant(() -> additive(() -> service.handlePredefinedData(CHILD, child))));

        assertNull(rowId);
        verify(modelService, never()).createOne(eq(CHILD), anyMap());
    }

    @Test
    void anAdditiveLoadWritesANewRowReferringToADeactivatedRow() {
        // The tenant deactivated the parent rather than deleting it, and a patch adds a child under it.
        // Deactivated is not deleted: the child goes in.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        storedRow(PARENT, TYPED_ROW_ID, false);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);
        Map<String, Object> child = new LinkedHashMap<>(childRow());
        child.put(CHILD_FK, "tenant_option_set.OrganizationType");

        Serializable rowId = withMetadata(() -> inTenant(() -> additive(() -> service.handlePredefinedData(CHILD, child))));

        assertEquals(555L, rowId);
    }

    @Test
    void anAdditiveLoadReachingAParentByIdAloneWritesNothingToIt() {
        // A patch adding an item under an existing option set names the set by its id only.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        storedRow(PARENT, TYPED_ROW_ID, true);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put("id", "tenant_option_set.OrganizationType");
        parent.put("optionItems", List.of(childRow()));

        Serializable rowId = withMetadata(() -> inTenant(() -> additive(() -> service.handlePredefinedData(PARENT, parent))));

        assertEquals(TYPED_ROW_ID, rowId);
        verify(modelService, never()).updateOne(eq(PARENT), anyMap());
        verify(modelService).createOne(eq(CHILD), anyMap());
    }

    @Test
    void anAppendLoadWritesNothingToARowTheScopeHasAndCreatesTheNewRowsUnderIt() {
        // The tenant has the option set, and with it a seeded item the file no longer lists; the file now lists
        // an item the tenant lacks.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false),
                binding(CHILD, "tenant_option_item.OrganizationType.Old", "999", false));
        storedRow(PARENT, TYPED_ROW_ID, true);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);

        LoadedRows written = SysPreDataServiceImpl.reportingWrites(() -> withMetadata(() ->
                inTenant(() -> appending(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))))));

        // What the tenant has is the tenant's, whatever the file says of it now: nothing is written to the row,
        // and nothing under it is deleted.
        verify(modelService, never()).updateOne(eq(PARENT), anyMap());
        verify(modelService, never()).deleteByIds(eq(CHILD), anyList());
        verify(modelService).createOne(eq(CHILD), anyMap());
        assertEquals(Map.of(CHILD, List.of("555")), written.created());
        assertEquals(Map.of(), written.updated());
    }

    @Test
    void anAppendLoadLeavesTheRowsTheScopeHasAsTheyAre() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false),
                binding(CHILD, "tenant_option_item.OrganizationType.Company", "555", false));
        storedRow(PARENT, TYPED_ROW_ID, true);
        storedRow(CHILD, 555L, true);

        LoadedRows written = SysPreDataServiceImpl.reportingWrites(() -> withMetadata(() ->
                inTenant(() -> appending(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))))));

        assertTrue(written.isEmpty(), written.toString());
        verify(modelService, never()).updateOne(any(), anyMap());
        verify(modelService, never()).createOne(any(), anyMap());
    }

    @Test
    void anAppendLoadCreatesARowTheScopeLacks() {
        bindings();
        when(modelService.createOne(eq(PARENT), anyMap())).thenReturn(TYPED_ROW_ID);
        when(modelService.createOne(eq(CHILD), anyMap())).thenReturn(555L);

        Serializable rowId = withMetadata(() ->
                inTenant(() -> appending(() -> service.handlePredefinedData(PARENT, parentRow(childRow())))));

        assertEquals(TYPED_ROW_ID, rowId);
        verify(modelService).createOne(eq(PARENT), anyMap());
        verify(modelService).createOne(eq(CHILD), anyMap());
    }

    @Test
    void anAppendLoadLeavesADeletedRowDeletedWithWhatIsNestedInIt() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        // The tenant deleted the seeded row.
        when(modelService.searchList(eq(PARENT), any(FlexQuery.class))).thenReturn(List.of());

        Serializable rowId = withMetadata(() ->
                inTenant(() -> appending(() -> service.handlePredefinedData(PARENT, parentRow(childRow())))));

        assertNull(rowId);
        verify(modelService, never()).createOne(eq(PARENT), anyMap());
        verify(modelService, never()).createOne(eq(CHILD), anyMap());
    }

    @Test
    void aFrozenParentLeavesItsChildrenAlone() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, true));

        Serializable rowId = withMetadata(() ->
                inTenant(() -> service.handlePredefinedData(PARENT, parentRow(childRow()))));

        assertEquals(TYPED_ROW_ID, rowId);
        // A freeze covers the record, not half of it. The destructive half of a re-load is exactly
        // this child sync — it deletes every child the file no longer declares.
        verify(modelService, never()).searchList(eq(CHILD), any(FlexQuery.class));
        verify(modelService, never()).deleteByFilters(eq(CHILD), any(Filters.class));
        verify(modelService, never()).createOne(eq(CHILD), anyMap());
        verify(modelService, never()).updateOne(eq(PARENT), anyMap());
    }

    @Test
    void anInactiveRowTheReloadLeavesUnchangedIsNotInsertedAgain() {
        // The seeded row is inactive — deactivated on the page, or declared so by its own file — and the
        // re-load changes nothing in it, so the update reports no change. Read the ordinary way, which adds
        // `active = true`, the row looked gone and was inserted a second time: a duplicate primary key on a
        // code-as-id model, a second copy on any other.
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(false);
        storedRow(PARENT, TYPED_ROW_ID, false);

        Serializable rowId = withMetadata(() -> inTenant(() -> service.handlePredefinedData(PARENT, parentRow())));

        assertEquals(TYPED_ROW_ID, rowId);
        verify(modelService, never()).createOne(eq(PARENT), anyMap());
        // Nor is the binding re-pointed.
        verify(service, never()).updateOne(any(SysPreData.class));
    }

    @Test
    void aBindingWhoseRowIsGoneIsRecreatedWithoutInheritingTheClearedFields() {
        bindings(binding(PARENT, "tenant_option_set.OrganizationType", STORED_ROW_ID, false));
        // The row the binding names is gone — someone deleted the seeded row through the ordinary UI.
        when(modelService.updateOne(eq(PARENT), anyMap())).thenReturn(false);
        when(modelService.searchList(eq(PARENT), any(FlexQuery.class))).thenReturn(List.of());
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

    /**
     * The binding ledger: a lookup answers with the bindings of its model whose preId — or row id, for the
     * lookups that ask which rows a seed created — it names.
     */
    private void bindings(SysPreData... found) {
        List<SysPreData> ledger = new ArrayList<>(List.of(found));
        doAnswer(call -> {
            String filter = call.getArgument(0).toString();
            return ledger.stream()
                    .filter(b -> filter.contains("\"" + b.getModel() + "\""))
                    .filter(b -> filter.contains("\"" + b.getPreId() + "\"") || filter.contains("\"" + b.getRowId() + "\""))
                    .toList();
        }).when(service).searchList(any(Filters.class));
    }

    /** The row a binding names, as the database holds it. */
    private void storedRow(String model, Serializable rowId, boolean active) {
        when(modelService.searchList(eq(model), reading(String.valueOf(rowId), active)))
                .thenReturn(List.of(Map.of("id", rowId)));
    }

    /** The children stored under the parent, as the database holds them. */
    private void storedChildren(boolean active, Long... ids) {
        when(modelService.searchList(eq(CHILD), reading(CHILD_FK, active)))
                .thenReturn(Arrays.stream(ids).<Map<String, Object>>map(id -> Map.of("id", id)).toList());
    }

    /**
     * A read that finds the rows: its filters name {@code subject}, and — the rows being inactive — it goes past
     * active control, as an ordinary read adds {@code active = true} and does not see them.
     */
    private static FlexQuery reading(String subject, boolean active) {
        return argThat(query -> query != null
                && (active || query.getFilterControl().isSkipActiveControl())
                && query.getFilters().toString().contains(subject));
    }

    private static Serializable additive(Supplier<Serializable> action) {
        return SysPreDataServiceImpl.inMode(LoadMode.ADDITIVE, action);
    }

    private static Serializable appending(Supplier<Serializable> action) {
        return SysPreDataServiceImpl.inMode(LoadMode.APPEND, action);
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
