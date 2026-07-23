package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.entity.TimelineSlice;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.relation.RelationDeleteHandler;
import io.softa.framework.orm.service.versioning.TimelineStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests pinning the CURRENT timeline interval-maintenance behavior
 * ({@link TimelineServiceImpl} split / heal / correct-neighbor matrix) plus the timeline
 * arms of the entity-delete path, BEFORE the VersioningStrategy seam refactor relocates
 * the algorithm. The algorithm must keep passing these tests verbatim after the move
 * (this class is then re-pointed at the relocated implementation).
 *
 * <p>Also pins two delete-path facts the refactor must not disturb:
 * <ul>
 *   <li>entity-delete prefetch is UNCLAMPED — {@code deleteByIds} reads all slices via
 *       {@code jdbcService.selectByIds} (never the effective-date-filtered read path), so an
 *       entity whose slices are all in the past remains deletable;</li>
 *   <li>the inbound-FK delete handler currently receives the logical id repeated once per
 *       slice (deduplication is a planned follow-up change).</li>
 * </ul>
 */
class ContinuousIntervalMaintainerTest {

    private static final String MODEL = "DeptInfo";
    private static final LocalDate EFFECTIVE = LocalDate.of(2025, 6, 15);
    private static final LocalDate MAX_END = ModelConstant.MAX_EFFECTIVE_END_DATE;

    private MockedStatic<ModelManager> modelManager;
    private JdbcService<Serializable> jdbc;
    private TimelineServiceImpl<Serializable> timeline;

    // Routed results for the three internal selectByFilter probes; tests fill these per scenario.
    private final List<Map<String, Object>> overlapResult = new ArrayList<>();
    private final List<Map<String, Object>> nextResult = new ArrayList<>();
    private final List<Map<String, Object>> sliceLookupResult = new ArrayList<>();
    private final List<FlexQuery> recordedSelects = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.isTimelineModel(MODEL)).thenReturn(true);
        modelManager.when(() -> ModelManager.getIdStrategy(MODEL)).thenReturn(IdStrategy.DISTRIBUTED_LONG);
        // The algorithm mutates the returned set (removeAll/retainAll/remove): a fresh copy per call.
        modelManager.when(() -> ModelManager.getModelUpdatableFields(MODEL))
                .thenAnswer(inv -> new HashSet<>(List.of(
                        "name", "code",
                        ModelConstant.EFFECTIVE_START_DATE, ModelConstant.EFFECTIVE_END_DATE)));
        modelManager.when(() -> ModelManager.getModelField(MODEL, ModelConstant.SLICE_ID))
                .thenReturn(metaField(ModelConstant.SLICE_ID, FieldType.LONG));

        jdbc = Mockito.mock(JdbcService.class);
        // Route the three internal probes by which timeline fields the filter mentions:
        // overlap probe filters on both effective dates; next probe on start only; slice lookup on sliceId only.
        when(jdbc.selectByFilter(eq(MODEL), any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery query = inv.getArgument(1);
            recordedSelects.add(query);
            String filterString = String.valueOf(query.getFilters());
            if (filterString.contains(ModelConstant.EFFECTIVE_END_DATE)) {
                return copyRows(overlapResult);
            }
            if (filterString.contains(ModelConstant.EFFECTIVE_START_DATE)) {
                return copyRows(nextResult);
            }
            return copyRows(sliceLookupResult);
        });

        timeline = new TimelineServiceImpl<>();
        ReflectionTestUtils.setField(timeline, "jdbcService", jdbc);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    // ---------------------------------------------------------------- createSlices

    @Test
    void createFirstSliceWithoutIdDefaultsStartToContextAndEndToMax() {
        Map<String, Object> row = mutableRow(Map.of("name", "A"));

        List<Map<String, Object>> result = withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        Map<String, Object> inserted = captureSingleInsert();
        Assertions.assertEquals(EFFECTIVE, inserted.get(ModelConstant.EFFECTIVE_START_DATE));
        Assertions.assertEquals(MAX_END, inserted.get(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertSame(row, result.getFirst());
    }

    @Test
    void createSliceSplittingOverlappedSliceShortensItAndCopiesMissingFields() {
        when(jdbc.exist(MODEL, 1L)).thenReturn(true);
        overlapResult.add(mutableRow(Map.of(
                ModelConstant.ID, 1L, ModelConstant.SLICE_ID, 11L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 1, 1),
                ModelConstant.EFFECTIVE_END_DATE, LocalDate.of(2025, 12, 31),
                "name", "old", "code", "D1")));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.ID, 1L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 6, 15),
                "name", "B"));

        withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        // Overlapped slice end corrected to newStart - 1.
        Map<String, Object> corrected = captureSingleUpdateOne();
        Assertions.assertEquals(11L, corrected.get(ModelConstant.SLICE_ID));
        Assertions.assertEquals(LocalDate.of(2025, 6, 14), corrected.get(ModelConstant.EFFECTIVE_END_DATE));
        // New slice takes the overlapped slice's old end, copies the missing 'code', never its sliceId.
        Map<String, Object> inserted = captureSingleInsert();
        Assertions.assertEquals(LocalDate.of(2025, 12, 31), inserted.get(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertEquals("B", inserted.get("name"));
        Assertions.assertEquals("D1", inserted.get("code"));
        Assertions.assertFalse(inserted.containsKey(ModelConstant.SLICE_ID));
    }

    @Test
    void createSliceWithSameStartCorrectsOverlappedSliceInPlace() {
        when(jdbc.exist(MODEL, 1L)).thenReturn(true);
        overlapResult.add(mutableRow(Map.of(
                ModelConstant.ID, 1L, ModelConstant.SLICE_ID, 11L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 1, 1),
                ModelConstant.EFFECTIVE_END_DATE, LocalDate.of(2025, 12, 31))));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.ID, 1L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 1, 1),
                "name", "C"));

        withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        // Correct-in-place: adopts the overlapped sliceId, updates without touching effectiveEndDate.
        Assertions.assertEquals(11L, row.get(ModelConstant.SLICE_ID));
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertTrue(update.fields.contains("name"));
        Assertions.assertFalse(update.fields.contains(ModelConstant.EFFECTIVE_END_DATE));
        verify(jdbc, never()).insertList(any(), anyList());
        verify(jdbc, never()).updateOne(any(), any());
    }

    @Test
    void createSliceBeforeNextSliceEndsOneDayBeforeNextStart() {
        when(jdbc.exist(MODEL, 1L)).thenReturn(true);
        nextResult.add(mutableRow(Map.of(
                ModelConstant.ID, 1L, ModelConstant.SLICE_ID, 12L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 9, 1),
                ModelConstant.EFFECTIVE_END_DATE, MAX_END,
                "name", "N")));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.ID, 1L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 6, 15)));

        withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        Map<String, Object> inserted = captureSingleInsert();
        Assertions.assertEquals(LocalDate.of(2025, 8, 31), inserted.get(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertEquals("N", inserted.get("name"));
        Assertions.assertFalse(inserted.containsKey(ModelConstant.SLICE_ID));
        verify(jdbc, never()).updateOne(any(), any());
    }

    @Test
    void createSliceWithNoNeighborsInsertsAsFirstSlice() {
        when(jdbc.exist(MODEL, 1L)).thenReturn(true);
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.ID, 1L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 6, 15)));

        withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        Map<String, Object> inserted = captureSingleInsert();
        Assertions.assertEquals(MAX_END, inserted.get(ModelConstant.EFFECTIVE_END_DATE));
    }

    // ------------------------------------------------- web rows carry ISO date strings

    @Test
    void createFirstSliceAcceptsIsoDateStringFromWebRow() {
        // The type-cast pipeline only runs at the store step; the interval algorithm itself
        // must normalize incoming date strings at entry.
        Map<String, Object> row = mutableRow(Map.of("name", "A",
                ModelConstant.EFFECTIVE_START_DATE, "2025-06-15"));

        withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        Map<String, Object> inserted = captureSingleInsert();
        Assertions.assertEquals(LocalDate.of(2025, 6, 15), inserted.get(ModelConstant.EFFECTIVE_START_DATE));
        Assertions.assertEquals(MAX_END, inserted.get(ModelConstant.EFFECTIVE_END_DATE));
    }

    @Test
    void updateAcceptsIsoDateStringFromWebRow() {
        // Same scenario as updateMovingStartWithinItself..., but the start date arrives as a
        // String — the entry normalization must make the interval math behave identically.
        sliceLookupResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 5, 31)));
        overlapResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 5, 31)));
        when(jdbc.getIds(eq(MODEL), eq(ModelConstant.SLICE_ID), any(FlexQuery.class)))
                .thenReturn(List.of((Serializable) 10L));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 11L,
                ModelConstant.EFFECTIVE_START_DATE, "2025-03-01"));

        withCtx(() -> timeline.updateSlices(MODEL, listOf(row)));

        Map<String, Object> corrected = captureSingleUpdateOne();
        Assertions.assertEquals(LocalDate.of(2025, 2, 28), corrected.get(ModelConstant.EFFECTIVE_END_DATE));
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertEquals(LocalDate.of(2025, 3, 1), update.row().get(ModelConstant.EFFECTIVE_START_DATE));
    }

    // ------------------------------------------------- create guard: unknown caller-supplied id

    @Test
    void createWithUnknownIdIsRejectedForDistributedStrategies() {
        // exist(99) defaults to false: the id matches no entity — a typo must fail loudly
        // instead of silently minting a new entity with a caller-chosen id.
        Map<String, Object> row = mutableRow(Map.of(ModelConstant.ID, 99L, "name", "typo"));

        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> withCtx(() -> timeline.createSlices(MODEL, listOf(row))));
        Assertions.assertTrue(e.getMessage().contains("does not exist"));
        verify(jdbc, never()).insertList(any(), anyList());
    }

    @Test
    void createWithUnknownIdIsAllowedForExternalIdModels() {
        // EXTERNAL_ID models legitimately create new entities with a caller-supplied id.
        modelManager.when(() -> ModelManager.getIdStrategy(MODEL)).thenReturn(IdStrategy.EXTERNAL_ID);
        Map<String, Object> row = mutableRow(Map.of(ModelConstant.ID, 99L, "name", "ext"));

        withCtx(() -> timeline.createSlices(MODEL, listOf(row)));

        Map<String, Object> inserted = captureSingleInsert();
        Assertions.assertEquals(99L, inserted.get(ModelConstant.ID));
        Assertions.assertEquals(MAX_END, inserted.get(ModelConstant.EFFECTIVE_END_DATE));
    }

    @Test
    void createWithUnknownIdIsAllowedInInsertIdImportMode() {
        // enableInsertId is the preset-id import escape hatch (mirrors IdProcessor's contract).
        boolean previous = SystemConfig.env.isEnableInsertId();
        SystemConfig.env.setEnableInsertId(true);
        try {
            Map<String, Object> row = mutableRow(Map.of(ModelConstant.ID, 99L, "name", "import"));
            withCtx(() -> timeline.createSlices(MODEL, listOf(row)));
            Map<String, Object> inserted = captureSingleInsert();
            Assertions.assertEquals(99L, inserted.get(ModelConstant.ID));
        } finally {
            SystemConfig.env.setEnableInsertId(previous);
        }
    }

    // ------------------------------------------------- addVersion precondition (strategy level)

    @Test
    void checkVersionCreateRequiresAnExistingEntityId() {
        TimelineStrategy<Serializable> strategy = new TimelineStrategy<>(jdbc, timeline);

        RuntimeException missingId = Assertions.assertThrows(RuntimeException.class,
                () -> strategy.checkVersionCreate(MODEL, mutableRow(Map.of("name", "x"))));
        Assertions.assertTrue(missingId.getMessage().contains("requires the existing entity"));

        // DISTRIBUTED strategies defer existence to the createSlices guard, which rides the
        // exist() probe it performs anyway — checkVersionCreate must NOT probe a second time.
        Assertions.assertDoesNotThrow(
                () -> strategy.checkVersionCreate(MODEL, mutableRow(Map.of(ModelConstant.ID, 99L))));
        verify(jdbc, never()).exist(eq(MODEL), any());

        // EXTERNAL_ID leaves the createSlices guard open (new entities legitimately carry an id),
        // so addVersion's "existing entity" contract needs its own probe there.
        modelManager.when(() -> ModelManager.getIdStrategy(MODEL)).thenReturn(IdStrategy.EXTERNAL_ID);
        RuntimeException unknownId = Assertions.assertThrows(RuntimeException.class,
                () -> strategy.checkVersionCreate(MODEL, mutableRow(Map.of(ModelConstant.ID, 99L))));
        Assertions.assertTrue(unknownId.getMessage().contains("does not exist"));

        when(jdbc.exist(MODEL, 1L)).thenReturn(true);
        Assertions.assertDoesNotThrow(
                () -> strategy.checkVersionCreate(MODEL, mutableRow(Map.of(ModelConstant.ID, 1L))));
    }

    // ---------------------------------------------------------------- updateSlices

    @Test
    void updateWithoutStartDateUpdatesCurrentSliceAndStripsEndDate() {
        Map<String, Object> row = mutableRow(Map.of(ModelConstant.SLICE_ID, 11, "name", "X"));

        Integer count = withCtx(() -> timeline.updateSlices(MODEL, listOf(row)));

        Assertions.assertEquals(1, count);
        // Integer sliceId is normalized to the Long PK type before use.
        Assertions.assertEquals(11L, row.get(ModelConstant.SLICE_ID));
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertEquals(Set.of("name"), update.fields);
    }

    @Test
    void updateWithEmptyStartDateIsRejected() {
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 11L, ModelConstant.EFFECTIVE_START_DATE, ""));

        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> withCtx(() -> timeline.updateSlices(MODEL, listOf(row))));
        Assertions.assertTrue(e.getMessage().contains("cannot be set to empty"));
    }

    @Test
    void updateMovingStartWithinItselfHealsPreviousSliceEnd() {
        sliceLookupResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 5, 31)));
        overlapResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 5, 31)));
        when(jdbc.getIds(eq(MODEL), eq(ModelConstant.SLICE_ID), any(FlexQuery.class)))
                .thenReturn(List.of((Serializable) 10L));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 11L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 3, 1)));

        withCtx(() -> timeline.updateSlices(MODEL, listOf(row)));

        // Previous slice (end = oldStart - 1) is stretched to newStart - 1.
        Map<String, Object> corrected = captureSingleUpdateOne();
        Assertions.assertEquals(10L, corrected.get(ModelConstant.SLICE_ID));
        Assertions.assertEquals(LocalDate.of(2025, 2, 28), corrected.get(ModelConstant.EFFECTIVE_END_DATE));
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertFalse(update.fields.contains(ModelConstant.EFFECTIVE_END_DATE));
    }

    @Test
    void updateCollidingWithAnotherSliceAtSameStartIsRejected() {
        sliceLookupResult.add(sliceRow(1L, 12L, LocalDate.of(2025, 6, 1), MAX_END));
        overlapResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 5, 31)));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 12L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 1, 1)));

        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> withCtx(() -> timeline.updateSlices(MODEL, listOf(row))));
        Assertions.assertTrue(e.getMessage().contains("different sliceId"));
        verify(jdbc, never()).updateList(any(), anyList(), any());
    }

    @Test
    void updateOverlappingAdjacentPreviousSliceShortensIt() {
        sliceLookupResult.add(sliceRow(1L, 12L, LocalDate.of(2025, 6, 1), MAX_END));
        overlapResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 5, 31)));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 12L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 4, 1)));

        withCtx(() -> timeline.updateSlices(MODEL, listOf(row)));

        Map<String, Object> corrected = captureSingleUpdateOne();
        Assertions.assertEquals(11L, corrected.get(ModelConstant.SLICE_ID));
        Assertions.assertEquals(LocalDate.of(2025, 3, 31), corrected.get(ModelConstant.EFFECTIVE_END_DATE));
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertFalse(update.fields.contains(ModelConstant.EFFECTIVE_END_DATE));
    }

    @Test
    void updateOverlappingNonAdjacentSliceCorrectsThreeSlices() {
        sliceLookupResult.add(sliceRow(1L, 13L, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 12, 31)));
        overlapResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 5, 31)));
        when(jdbc.getIds(eq(MODEL), eq(ModelConstant.SLICE_ID), any(FlexQuery.class)))
                .thenReturn(List.of((Serializable) 12L));
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 13L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 2, 1)));

        withCtx(() -> timeline.updateSlices(MODEL, listOf(row)));

        // 1) overlapped slice end -> newStart - 1;  2) original's predecessor absorbs the old range.
        List<Map<String, Object>> corrections = captureUpdateOnes(2);
        Assertions.assertEquals(11L, corrections.get(0).get(ModelConstant.SLICE_ID));
        Assertions.assertEquals(LocalDate.of(2025, 1, 31), corrections.get(0).get(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertEquals(12L, corrections.get(1).get(ModelConstant.SLICE_ID));
        Assertions.assertEquals(LocalDate.of(2025, 12, 31), corrections.get(1).get(ModelConstant.EFFECTIVE_END_DATE));
        // 3) the moved slice itself takes the overlapped slice's end — the one case end is writable.
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertTrue(update.fields.contains(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertEquals(LocalDate.of(2025, 5, 31), update.row.get(ModelConstant.EFFECTIVE_END_DATE));
    }

    @Test
    void updateMovingPastAllSlicesEndsBeforeNextAndHealsSilentlyWhenNoPredecessor() {
        sliceLookupResult.add(sliceRow(1L, 11L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)));
        nextResult.add(sliceRow(1L, 12L, LocalDate.of(2025, 10, 1), MAX_END));
        // No predecessor matches the heal probe: the correction silently skips (documented gap behavior).
        when(jdbc.getIds(eq(MODEL), eq(ModelConstant.SLICE_ID), any(FlexQuery.class)))
                .thenReturn(List.of());
        Map<String, Object> row = mutableRow(Map.of(
                ModelConstant.SLICE_ID, 11L,
                ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 7, 1)));

        withCtx(() -> timeline.updateSlices(MODEL, listOf(row)));

        verify(jdbc, never()).updateOne(any(), any());
        UpdateListCall update = captureSingleUpdateList();
        Assertions.assertTrue(update.fields.contains(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertEquals(LocalDate.of(2025, 9, 30), update.row.get(ModelConstant.EFFECTIVE_END_DATE));
        // The next-slice probe is a limit-1 ascending scan (start-only filter; the overlap probe also
        // mentions the end date, so exclude it).
        FlexQuery nextProbe = recordedSelects.stream()
                .filter(q -> {
                    String filterString = String.valueOf(q.getFilters());
                    return filterString.contains(ModelConstant.EFFECTIVE_START_DATE)
                            && !filterString.contains(ModelConstant.EFFECTIVE_END_DATE);
                })
                .findFirst().orElseThrow();
        Assertions.assertEquals(1, nextProbe.getLimitSize());
        Assertions.assertNotNull(nextProbe.getOrders());
    }

    // ---------------------------------------------------------------- deleteSlice

    @Test
    void deleteSliceHealsPredecessorToAbsorbDeletedRange() {
        when(jdbc.getIds(eq(MODEL), eq(ModelConstant.SLICE_ID), any(FlexQuery.class)))
                .thenReturn(List.of((Serializable) 11L));
        when(jdbc.deleteBySliceId(MODEL, 12L)).thenReturn(true);
        TimelineSlice slice = new TimelineSlice();
        slice.setId(1L);
        slice.setSliceId(12L);
        slice.setEffectiveStartDate(LocalDate.of(2025, 6, 1));
        slice.setEffectiveEndDate(LocalDate.of(2025, 12, 31));

        boolean deleted = withCtx(() -> timeline.deleteSlice(MODEL, slice));

        Assertions.assertTrue(deleted);
        Map<String, Object> corrected = captureSingleUpdateOne();
        Assertions.assertEquals(11L, corrected.get(ModelConstant.SLICE_ID));
        Assertions.assertEquals(LocalDate.of(2025, 12, 31), corrected.get(ModelConstant.EFFECTIVE_END_DATE));
        verify(jdbc).deleteBySliceId(MODEL, 12L);
    }

    // ---------------------------------------------------------------- appendTimelineFilters

    @Test
    void filtersOverloadClampsUnlessCallerAlreadyFiltersOnEffectiveDates() {
        // Non-timeline models pass through untouched (same instance).
        modelManager.when(() -> ModelManager.isTimelineModel("Plain")).thenReturn(false);
        Filters plain = new Filters().eq("name", "x");
        Assertions.assertSame(plain, withCtx(() -> timeline.appendTimelineFilters("Plain", plain)));

        // Empty filters -> pure effective-date clamp at the context date.
        Filters clamped = withCtx(() -> timeline.appendTimelineFilters(MODEL, new Filters()));
        String clampString = String.valueOf(clamped);
        Assertions.assertTrue(clampString.contains(ModelConstant.EFFECTIVE_START_DATE));
        Assertions.assertTrue(clampString.contains(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertTrue(clampString.contains(EFFECTIVE.toString()));

        // Caller-supplied effective-date conditions opt out of the default clamp (dual-trigger contract).
        Filters selfFiltered = new Filters().eq(ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 1, 1));
        Assertions.assertSame(selfFiltered, withCtx(() -> timeline.appendTimelineFilters(MODEL, selfFiltered)));

        // Ordinary conditions get the clamp ANDed on.
        Filters ordinary = new Filters().eq("name", "x");
        Filters combined = withCtx(() -> timeline.appendTimelineFilters(MODEL, ordinary));
        Assertions.assertNotSame(ordinary, combined);
        Assertions.assertTrue(String.valueOf(combined).contains(ModelConstant.EFFECTIVE_END_DATE));
    }

    @Test
    void flexQueryOverloadTreatsExplicitFlagAndEffectiveDateFiltersAsAcross() {
        // Explicit acrossTimeline opts out even for a timeline model.
        FlexQuery across = new FlexQuery(new Filters().eq("name", "x")).acrossTimelineData();
        Assertions.assertSame(across.getFilters(), withCtx(() -> timeline.appendTimelineFilters(MODEL, across)));

        // FlexQuery.isAcrossTimeline() itself sniffs effective-date conditions, so the dual trigger
        // (explicit flag OR caller-supplied effective-date filters) is uniform across both overloads.
        FlexQuery selfFiltered = new FlexQuery(
                new Filters().eq(ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(2025, 1, 1)));
        Assertions.assertSame(selfFiltered.getFilters(),
                withCtx(() -> timeline.appendTimelineFilters(MODEL, selfFiltered)));

        // Ordinary conditions get the clamp ANDed on.
        FlexQuery ordinary = new FlexQuery(new Filters().eq("name", "x"));
        Filters clamped = withCtx(() -> timeline.appendTimelineFilters(MODEL, ordinary));
        String clampString = String.valueOf(clamped);
        Assertions.assertTrue(clampString.contains(ModelConstant.EFFECTIVE_START_DATE));
        Assertions.assertTrue(clampString.contains(ModelConstant.EFFECTIVE_END_DATE));
        Assertions.assertTrue(clampString.contains(EFFECTIVE.toString()));
    }

    // ---------------------------------------------------------------- entity-delete pins

    @Test
    void entityDeletePrefetchIsUnclampedAndHandlerReceivesPerSliceLogicalIds() {
        modelManager.when(() -> ModelManager.isSoftDeleted(MODEL)).thenReturn(false);
        ModelServiceImpl<Serializable> modelService = new ModelServiceImpl<>();
        JdbcService<Serializable> deleteJdbc = Mockito.mock(JdbcService.class);
        PermissionService permission = Mockito.mock(PermissionService.class);
        RelationDeleteHandler handler = Mockito.mock(RelationDeleteHandler.class);
        VersioningStrategyResolver versioning = Mockito.mock(VersioningStrategyResolver.class);
        ReflectionTestUtils.setField(modelService, "jdbcService", deleteJdbc);
        ReflectionTestUtils.setField(modelService, "permissionService", permission);
        ReflectionTestUtils.setField(modelService, "relationDeleteHandler", handler);
        ReflectionTestUtils.setField(modelService, "versioning", versioning);

        // All slices of entity 1 lie in the past: none is effective at the context date.
        // (Mutable list: Assert.allNotNull probes contains(null), which List.of rejects.)
        List<Serializable> ids = new ArrayList<>(List.of(1L));
        when(deleteJdbc.selectByIds(MODEL, ids, Collections.emptyList(), ConvertType.ORIGINAL))
                .thenReturn(List.of(
                        sliceRow(1L, 11L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 5, 31)),
                        sliceRow(1L, 12L, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 9, 30)),
                        sliceRow(1L, 13L, LocalDate.of(2024, 10, 1), LocalDate.of(2024, 12, 31))));
        when(deleteJdbc.deleteByIds(eq(MODEL), anyList(), anyList())).thenReturn(true);

        boolean deleted = withCtx(() -> modelService.deleteByIds(MODEL, ids));

        Assertions.assertTrue(deleted);
        // Prefetch goes through the unclamped by-id read — never the effective-date-filtered path.
        verify(deleteJdbc).selectByIds(MODEL, ids, Collections.emptyList(), ConvertType.ORIGINAL);
        verify(deleteJdbc, never()).selectByFilter(any(), any());
        verify(permission).checkIdsAccess(MODEL, ids, AccessType.DELETE);
        // Entity delete is versioning-agnostic: the strategy seam is never consulted on this path.
        verifyNoInteractions(versioning);

        // The inbound-FK handler runs once per ENTITY: the per-slice logical-id repetition is deduplicated.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Serializable>> handlerIds = ArgumentCaptor.forClass(List.class);
        verify(handler).handle(eq(MODEL), handlerIds.capture());
        Assertions.assertEquals(List.of(1L), handlerIds.getValue());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> deletedRows = ArgumentCaptor.forClass(List.class);
        verify(deleteJdbc).deleteByIds(eq(MODEL), anyList(), deletedRows.capture());
        Assertions.assertEquals(3, deletedRows.getValue().size());
    }

    @Test
    void softDeleteOfTimelineEntityMarksEverySliceDeletedByItsSliceId() {
        // Soft delete keys per-row updates off the pk taken from deletableRows — for a timeline
        // model that is one update per SLICE (pk = sliceId), covering the whole entity like the
        // physical branch's `WHERE id IN` does. Keying off the logical ids would probe
        // `WHERE slice_id = NULL` and silently delete nothing.
        modelManager.when(() -> ModelManager.isSoftDeleted(MODEL)).thenReturn(true);
        modelManager.when(() -> ModelManager.getSoftDeleteField(MODEL)).thenReturn("deleted");
        modelManager.when(() -> ModelManager.isActiveControl(MODEL)).thenReturn(false);
        modelManager.when(() -> ModelManager.getModelPrimaryKey(MODEL)).thenReturn(ModelConstant.SLICE_ID);
        modelManager.when(() -> ModelManager.getModel(MODEL)).thenReturn(new MetaModel());

        io.softa.framework.orm.jdbc.JdbcServiceImpl<Serializable> jdbcService =
                Mockito.spy(new io.softa.framework.orm.jdbc.JdbcServiceImpl<>());
        ReflectionTestUtils.setField(jdbcService, "changeLogPublisher",
                Mockito.mock(io.softa.framework.orm.changelog.ChangeLogPublisher.class));
        Mockito.doReturn(1).when(jdbcService).updateOne(eq(MODEL), any());

        List<Map<String, Object>> allSlices = List.of(
                sliceRow(1L, 11L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 5, 31)),
                sliceRow(1L, 12L, LocalDate.of(2024, 6, 1), MAX_END));
        boolean deleted = withCtx(() -> jdbcService.deleteByIds(MODEL, listSerializable(1L), allSlices));

        Assertions.assertTrue(deleted);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
        verify(jdbcService, Mockito.times(2)).updateOne(eq(MODEL), updates.capture());
        Assertions.assertEquals(List.of(11L, 12L),
                updates.getAllValues().stream().map(r -> r.get(ModelConstant.SLICE_ID)).toList());
        updates.getAllValues().forEach(update -> {
            Assertions.assertEquals(Boolean.TRUE, update.get("deleted"));
            Assertions.assertFalse(update.containsKey(ModelConstant.ID),
                    "the logical id must not sit in the SET clause");
        });
    }

    private static List<Serializable> listSerializable(Serializable value) {
        List<Serializable> values = new ArrayList<>();
        values.add(value);
        return values;
    }

    // ---------------------------------------------------------------- helpers

    private <T> T withCtx(Supplier<T> body) {
        Context context = new Context();
        context.setEffectiveDate(EFFECTIVE);
        AtomicReference<T> result = new AtomicReference<>();
        ContextHolder.runWith(context, () -> result.set(body.get()));
        return result.get();
    }

    private Map<String, Object> captureSingleInsert() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).insertList(eq(MODEL), captor.capture());
        Assertions.assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }

    private Map<String, Object> captureSingleUpdateOne() {
        return captureUpdateOnes(1).getFirst();
    }

    private List<Map<String, Object>> captureUpdateOnes(int expectedCalls) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jdbc, Mockito.times(expectedCalls)).updateOne(eq(MODEL), captor.capture());
        return captor.getAllValues();
    }

    private UpdateListCall captureSingleUpdateList() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> fieldsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(jdbc).updateList(eq(MODEL), rowsCaptor.capture(), fieldsCaptor.capture());
        Assertions.assertEquals(1, rowsCaptor.getValue().size());
        return new UpdateListCall(rowsCaptor.getValue().getFirst(), fieldsCaptor.getValue());
    }

    private record UpdateListCall(Map<String, Object> row, Set<String> fields) {}

    private static Map<String, Object> sliceRow(Long id, Long sliceId, LocalDate start, LocalDate end) {
        return mutableRow(Map.of(
                ModelConstant.ID, id, ModelConstant.SLICE_ID, sliceId,
                ModelConstant.EFFECTIVE_START_DATE, start, ModelConstant.EFFECTIVE_END_DATE, end));
    }

    private static Map<String, Object> mutableRow(Map<String, Object> values) {
        return new HashMap<>(values);
    }

    private static List<Map<String, Object>> listOf(Map<String, Object> row) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }

    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copies = new ArrayList<>();
        rows.forEach(row -> copies.add(new HashMap<>(row)));
        return copies;
    }

    private static MetaField metaField(String fieldName, FieldType fieldType) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        return metaField;
    }
}
