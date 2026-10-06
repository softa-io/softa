package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SysPreData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Re-loading a seed row of a timeline model. The binding names the entity, which has one stored row
 * per version; the seed row names its version by its effective start date and carries the entity's
 * latest version only.
 */
class SysPreDataTimelineTest {

    private static final String MODEL = "PayParameter";
    private static final String PRE_ID = "pay_param.sg.OW_Ceiling";
    private static final String STORED_ROW_ID = "880852479670394896";
    private static final Long ROW_ID = 880852479670394896L;
    private static final LocalDate Y2025 = LocalDate.of(2025, 1, 1);
    private static final LocalDate Y2026 = LocalDate.of(2026, 1, 1);

    private ModelService<Serializable> modelService;
    private SysPreDataServiceImpl service;

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
        doReturn(1L).when(service).createOne(any(SysPreData.class));
        doReturn(true).when(service).updateOne(any(SysPreData.class));
        doReturn(List.of(binding())).when(service).searchList(any(Filters.class));
    }

    @Test
    void aRowOnAStoredStartDateCorrectsThatVersion() {
        storedVersions(Y2025);

        Serializable rowId = load(row("8200.00", "2025-01-01"));

        assertEquals(ROW_ID, rowId);
        Map<String, Object> version = addedVersion();
        assertEquals(ROW_ID, version.get("id"));
        assertEquals(Y2025, version.get("effectiveStartDate"));
        assertEquals("8200.00", version.get("value"));
        // A timeline entity is never updated by id alone: that is what failed on every re-load.
        verify(modelService, never()).updateOne(eq(MODEL), anyMap());
    }

    @Test
    void aRowAfterTheLatestVersionAddsOne() {
        storedVersions(Y2025);

        load(row("8500.00", "2026-09-29"));

        Map<String, Object> version = addedVersion();
        assertEquals(LocalDate.of(2026, 9, 29), version.get("effectiveStartDate"));
        assertEquals("8500.00", version.get("value"));
    }

    @Test
    void anEarlierVersionIsCorrectedAndTheLaterOnesAreLeftAlone() {
        // A version from 2026 was added on the page; the file still describes the 2025 one.
        storedVersions(Y2025, Y2026);

        load(row("8200.00", "2025-01-01"));

        assertEquals(Y2025, addedVersion().get("effectiveStartDate"));
    }

    @Test
    void aRowBeforeTheLatestVersionMatchingNoneIsRefused() {
        storedVersions(Y2025, Y2026);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> load(row("8200.00", "2025-06-01")));

        assertTrue(refused.getMessage().contains(PRE_ID), refused.getMessage());
        assertTrue(refused.getMessage().contains("2025-06-01"), refused.getMessage());
        verify(modelService, never()).addVersion(eq(MODEL), anyMap());
        verify(modelService, never()).updateOne(eq(MODEL), anyMap());
    }

    @Test
    void aRowWithoutAStartDateIsRefused() {
        storedVersions(Y2025);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> load(row("8200.00", null)));

        assertTrue(refused.getMessage().contains(PRE_ID), refused.getMessage());
        verify(modelService, never()).addVersion(eq(MODEL), anyMap());
    }

    @Test
    void fieldsTheFileLeavesOutAreClearedButNotTheComputedEndDate() {
        storedVersions(Y2025, Y2026);

        load(row("8000.00", "2025-01-01"));

        Map<String, Object> version = addedVersion();
        // "Clear what the seed no longer says" still holds for the model's own fields…
        assertTrue(version.containsKey("description"));
        assertNull(version.get("description"));
        // …but the 2025 version ends the day before 2026 began; clearing that would let it run on under
        // the 2026 one.
        assertFalse(version.containsKey("effectiveEndDate"), version.toString());
        assertFalse(version.containsKey("sliceId"), version.toString());
    }

    @Test
    void anEntityThatIsGoneIsRecreated() {
        when(modelService.exist(MODEL, ROW_ID)).thenReturn(false);
        when(modelService.createOne(eq(MODEL), anyMap())).thenReturn(999L);

        Serializable rowId = load(row("8000.00", "2025-01-01"));

        assertEquals(999L, rowId);
        verify(modelService, never()).addVersion(eq(MODEL), anyMap());
        ArgumentCaptor<SysPreData> rebound = ArgumentCaptor.forClass(SysPreData.class);
        verify(service).updateOne(rebound.capture());
        assertEquals("999", rebound.getValue().getRowId());
    }

    // ---- fixtures --------------------------------------------------------

    private static Map<String, Object> row(String value, String startDate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", PRE_ID);
        row.put("code", "OW_Ceiling");
        row.put("value", value);
        if (startDate != null) {
            row.put("effectiveStartDate", startDate);
        }
        return row;
    }

    private static SysPreData binding() {
        SysPreData preData = new SysPreData();
        preData.setModel(MODEL);
        preData.setPreId(PRE_ID);
        preData.setRowId(STORED_ROW_ID);
        preData.setFrozen(false);
        return preData;
    }

    private void storedVersions(LocalDate... starts) {
        when(modelService.exist(MODEL, ROW_ID)).thenReturn(true);
        List<Map<String, Object>> rows = Arrays.stream(starts)
                .map(start -> Map.<String, Object>of("effectiveStartDate", start))
                .toList();
        when(modelService.searchList(eq(MODEL), any(FlexQuery.class))).thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> addedVersion() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(modelService).addVersion(eq(MODEL), captor.capture());
        return captor.getValue();
    }

    /** A shared timeline model, loaded at system scope, with the model's metadata stubbed. */
    private Serializable load(Map<String, Object> row) {
        return withMetadata(() -> service.handlePredefinedData(MODEL, row));
    }

    private static Serializable withMetadata(Supplier<Serializable> action) {
        MetaField id = field(FieldType.LONG);
        MetaField code = field(FieldType.STRING);
        MetaField value = field(FieldType.STRING);
        MetaField start = field(FieldType.DATE);
        MetaModel shared = mock(MetaModel.class);
        when(shared.isMultiTenant()).thenReturn(false);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getModel(MODEL)).thenReturn(shared);
            mm.when(() -> ModelManager.isMultiTenantModel(MODEL)).thenReturn(false);
            mm.when(() -> ModelManager.isTimelineModel(MODEL)).thenReturn(true);
            mm.when(() -> ModelManager.getModelField(MODEL, "id")).thenReturn(id);
            mm.when(() -> ModelManager.getModelField(MODEL, "code")).thenReturn(code);
            mm.when(() -> ModelManager.getModelField(MODEL, "value")).thenReturn(value);
            mm.when(() -> ModelManager.getModelField(MODEL, "effectiveStartDate")).thenReturn(start);
            mm.when(() -> ModelManager.getIdStrategy(MODEL)).thenReturn(IdStrategy.DISTRIBUTED_LONG);
            // Mutable: the update removes the seeded keys from it in place.
            mm.when(() -> ModelManager.getModelUpdatableFieldsWithoutXToMany(MODEL)).thenReturn(new HashSet<>(
                    Set.of("code", "value", "description", "effectiveStartDate", "effectiveEndDate")));
            return action.get();
        }
    }

    private static MetaField field(FieldType fieldType) {
        MetaField metaField = mock(MetaField.class);
        when(metaField.getFieldType()).thenReturn(fieldType);
        return metaField;
    }
}
