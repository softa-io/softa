package io.softa.starter.seed.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An append load meeting a seed row the tenant has no binding for, while the tenant already has the row: one a
 * release script or someone on a page wrote, with no binding. Created again, it broke the businessKey's unique
 * index — a sequence's (tenant, code) — and the failure stopped the tenant's run at that file, before every file
 * after it. The row is bound instead, and left as it is.
 */
class SysPreDataAppendAdoptionTest {

    private static final String MODEL = "SysSequence";
    private static final Long TENANT = 7L;
    private static final String PRE_ID = "PayGroup.code";

    private ModelService<Serializable> modelService;
    private SysPreDataServiceImpl service;

    private static boolean previousMultiTenancy;

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        // Restored afterwards: the flag is global and the surefire JVM is shared with tests that read it.
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
        doReturn(1L).when(service).createOne(any(SysPreData.class));
        // No binding for any preId: the tenant's sequence came from a release script.
        List<SysPreData> ledger = new ArrayList<>();
        doAnswer(call -> ledger).when(service).searchList(any(Filters.class));
    }

    @Test
    void anAppendLoadBindsTheRowTheTenantAlreadyHas() {
        when(modelService.searchList(eq(MODEL), any(FlexQuery.class))).thenReturn(List.of(Map.of("id", 42L)));

        Serializable rowId = load(LoadMode.APPEND, List.of("code"));

        assertEquals(42L, rowId);
        verify(modelService, never()).createOne(eq(MODEL), anyMap());
        // Left as it is: the tenant's template and counter stand.
        verify(modelService, never()).updateOne(eq(MODEL), anyMap());

        ArgumentCaptor<SysPreData> binding = ArgumentCaptor.forClass(SysPreData.class);
        verify(service).createOne(binding.capture());
        assertEquals(MODEL, binding.getValue().getModel());
        assertEquals(PRE_ID, binding.getValue().getPreId());
        assertEquals("42", binding.getValue().getRowId());
        assertEquals(TENANT, binding.getValue().getTenantId());
    }

    @Test
    void theRowIsLookedUpByItsBusinessKeyPastActiveControl() {
        when(modelService.searchList(eq(MODEL), any(FlexQuery.class))).thenReturn(List.of(Map.of("id", 42L)));

        load(LoadMode.APPEND, List.of("code"));

        ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq(MODEL), query.capture());
        assertTrue(query.getValue().getFilters().toString().contains(PRE_ID), query.getValue().getFilters().toString());
        // A deactivated sequence is still there, and its code still taken.
        assertTrue(query.getValue().getFilterControl().isSkipActiveControl());
    }

    @Test
    void anAppendLoadCreatesTheRowTheTenantLacks() {
        when(modelService.searchList(eq(MODEL), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.createOne(eq(MODEL), anyMap())).thenReturn(99L);

        Serializable rowId = load(LoadMode.APPEND, List.of("code"));

        assertEquals(99L, rowId);
        verify(modelService).createOne(eq(MODEL), anyMap());
        verify(service).createOne(any(SysPreData.class));
    }

    @Test
    void aModelWithoutABusinessKeyIsCreatedAsBefore() {
        when(modelService.createOne(eq(MODEL), anyMap())).thenReturn(99L);

        Serializable rowId = load(LoadMode.APPEND, List.of());

        assertEquals(99L, rowId);
        // Nothing says which stored row would be the seed's, so nothing is looked up.
        verify(modelService, never()).searchList(eq(MODEL), any(FlexQuery.class));
    }

    @Test
    void severalRowsWithTheBusinessKeyRefuseTheSeedRow() {
        when(modelService.searchList(eq(MODEL), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 42L), Map.of("id", 43L)));

        assertThrows(RuntimeException.class, () -> load(LoadMode.APPEND, List.of("code")));
        verify(modelService, never()).createOne(eq(MODEL), anyMap());
        verify(service, never()).createOne(any(SysPreData.class));
    }

    // ---- fixtures --------------------------------------------------------

    private Serializable load(LoadMode mode, List<String> businessKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", PRE_ID);
        row.put("code", PRE_ID);
        row.put("template", "PG_{seq:5}");
        return withMetadata(businessKey,
                () -> inTenant(() -> SysPreDataServiceImpl.inMode(mode, () -> service.handlePredefinedData(MODEL, row))));
    }

    private static Serializable withMetadata(List<String> businessKey, Supplier<Serializable> action) {
        MetaField id = field(FieldType.LONG);
        MetaField code = field(FieldType.STRING);
        MetaField template = field(FieldType.STRING);
        MetaModel metaModel = mock(MetaModel.class);
        when(metaModel.isMultiTenant()).thenReturn(true);
        when(metaModel.getBusinessKey()).thenReturn(businessKey);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getModel(MODEL)).thenReturn(metaModel);
            mm.when(() -> ModelManager.isMultiTenantModel(MODEL)).thenReturn(true);
            mm.when(() -> ModelManager.getModelField(MODEL, "id")).thenReturn(id);
            mm.when(() -> ModelManager.getModelField(MODEL, "code")).thenReturn(code);
            mm.when(() -> ModelManager.getModelField(MODEL, "template")).thenReturn(template);
            mm.when(() -> ModelManager.getIdStrategy(MODEL)).thenReturn(IdStrategy.DB_AUTO_ID);
            return action.get();
        }
    }

    private static MetaField field(FieldType fieldType) {
        MetaField metaField = mock(MetaField.class);
        when(metaField.getFieldType()).thenReturn(fieldType);
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
