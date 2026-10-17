package io.softa.framework.orm.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code searchListIgnoringRowScope} waives, and what it does not.
 *
 * <p>The read pipeline's relation processors reach rows through it: expanding a relation has to cross
 * the caller's row range, or a referenced row's label blanks out and an owned child whose model has no
 * scope rule fails closed, taking the parent's sub-rows with it.
 *
 * <p>The field guards are a different question and stay on. They used to be waived along with the row
 * range — the pipeline ran wholesale under one context flag — so a caller holding no sensitive-field-set
 * grant read those fields in full by naming them in a sub-query on a related model, while reading the
 * same model directly gave them back masked.
 */
class RelationExpansionFieldMaskTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final JdbcService<Long> jdbcService = mock(JdbcService.class);
    private final VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
    private final VersioningStrategy strategy = mock(VersioningStrategy.class);

    private final ModelServiceImpl<Long> service = new ModelServiceImpl<>();

    private static final String MODEL = "EmpSalaryProfileItem";

    private ModelServiceImpl<Long> wire(List<Map<String, Object>> rowsFromDb) {
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "versioning", versioning);

        when(versioning.of(anyString())).thenReturn(strategy);
        when(strategy.scopeRead(anyString(), any(FlexQuery.class))).thenAnswer(i -> new Filters());
        // The field guard under test: `amount` is blocked, the rest is readable.
        when(permissionService.filterReadableFields(eq(MODEL), any(), eq(AccessType.READ)))
                .thenAnswer(i -> {
                    java.util.Collection<String> requested = i.getArgument(1);
                    List<String> out = new ArrayList<>(requested);
                    out.remove("amount");
                    return out;
                });
        when(permissionService.appendScopeAccessFilters(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.appendScopeAccessFiltersIgnoringRowScope(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        when(jdbcService.selectByFilter(anyString(), any(FlexQuery.class))).thenReturn(rowsFromDb);
        return service;
    }

    private static FlexQuery queryAskingForAmount() {
        return new FlexQuery(Set.of("id", "employeeId", "amount"), new Filters());
    }

    @Test
    void ignoringRowScope_doesNotAppendTheCallersRowRange() {
        wire(new ArrayList<>()).searchListIgnoringRowScope(MODEL, queryAskingForAmount());

        verify(permissionService, never()).appendScopeAccessFilters(anyString(), any(Filters.class));
    }

    @Test
    void plainSearchList_stillAppendsTheCallersRowRange() {
        wire(new ArrayList<>()).searchList(MODEL, queryAskingForAmount());

        verify(permissionService).appendScopeAccessFilters(eq(MODEL), any(Filters.class));
    }

    @Test
    void ignoringRowScope_stillDropsABlockedFieldFromTheProjection() {
        FlexQuery query = queryAskingForAmount();

        wire(new ArrayList<>()).searchListIgnoringRowScope(MODEL, query);

        // The column never reaches the SELECT — the same thing a direct read already did.
        assertThat(query.getFields()).containsExactlyInAnyOrder("id", "employeeId");
        verify(permissionService).checkModelFieldsAccess(eq(MODEL), any(), eq(AccessType.READ));
    }

    /**
     * The tests above all exercise the new path, so none of them notices a blanket bypass being put
     * back. This one does.
     *
     * <p>{@code @SkipPermissionCheck} on a read method is what caused the leak: the whole read
     * pipeline ran inside it, so every model the relation processors re-read from in there was
     * covered too — silently, transitively, and including the field mask. The jdbc layer never
     * consulted {@code PermissionService} to begin with, so the annotation bought nothing there; its
     * only effect was that reach. A read method that needs to skip the caller's row range says so by
     * calling {@code searchListIgnoringRowScope}, where the waiver is one call and greps.
     */
    @Test
    void jdbcReadMethodsCarryNoBlanketPermissionBypass() {
        List<String> offenders = java.util.Arrays.stream(
                        io.softa.framework.orm.jdbc.JdbcServiceImpl.class.getDeclaredMethods())
                .filter(m -> Set.of("selectByIds", "selectByFilter", "getIds", "selectByPage", "count")
                        .contains(m.getName()))
                .filter(m -> m.isAnnotationPresent(
                        io.softa.framework.orm.annotation.SkipPermissionCheck.class))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList();

        assertThat(offenders)
                .as("a read method under a blanket bypass also waives the field mask for every model "
                        + "the read pipeline re-reads from inside it")
                .isEmpty();
    }

    /**
     * What the row range waiver must NOT take with it.
     *
     * <p>The rewrite first: {@code appendScopeAccessFilters} rewrites a subtree condition into the id-path condition it
     * means BEFORE any of its own early returns, deliberately — an admin and a system-level read ask
     * the same question as anyone else. Skipping the whole scope step to skip the range would drop
     * that rewrite too, and an unrewritten subtree condition does not widen or narrow a result: it
     * compiles to a pattern match against an id and matches by coincidence.
     */
    @Test
    void ignoringRowScope_stillAppliesTheScopeIndependentRewrite() {
        wire(new ArrayList<>()).searchListIgnoringRowScope(MODEL, queryAskingForAmount());

        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(MODEL), any(Filters.class));
    }

    /**
     * The old entry point, pinned end to end.
     *
     * <p>The interesting failure is not the new path misbehaving, it is the new path quietly changing
     * the old one. Adding a variant meant factoring the body both share, and a step dropped in that
     * factoring costs every ordinary read — silently, because the variant's own tests still pass. So
     * this asserts the whole sequence in order rather than any single step: the guards, the timeline
     * clamp, the row range, the response mask.
     */
    @Test
    void plainSearchList_runsTheWholeSequenceInOrder() {
        ModelServiceImpl<Long> svc = wire(new ArrayList<>());

        svc.searchList(MODEL, queryAskingForAmount());

        var order = inOrder(permissionService, jdbcService);
        order.verify(permissionService).filterReadableFields(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).checkModelFieldsAccess(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).appendScopeAccessFilters(eq(MODEL), any(Filters.class));
        order.verify(jdbcService).selectByFilter(eq(MODEL), any(FlexQuery.class));
        order.verify(permissionService).maskResponseValue(eq(MODEL), any(), eq(AccessType.READ));
        order.verifyNoMoreInteractions();
    }

    /**
     * The delta between the two entry points is exactly one step, and this is what says so.
     *
     * <p>Written after a first attempt skipped the whole scope step to skip the row range, and took
     * the country narrowing and the subtree rewrite with it — both of which apply to every caller.
     * Each of those had a passing test of its own; nothing compared the two paths.
     */
    @Test
    void ignoringRowScope_differsFromPlainByTheRowRangeAlone() {
        ModelServiceImpl<Long> svc = wire(new ArrayList<>());

        svc.searchListIgnoringRowScope(MODEL, queryAskingForAmount());

        var order = inOrder(permissionService, jdbcService);
        order.verify(permissionService).filterReadableFields(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).checkModelFieldsAccess(eq(MODEL), any(), eq(AccessType.READ));
        // The one substitution: everything but the range, where the plain path also takes the range.
        order.verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(MODEL), any(Filters.class));
        order.verify(jdbcService).selectByFilter(eq(MODEL), any(FlexQuery.class));
        order.verify(permissionService).maskResponseValue(eq(MODEL), any(), eq(AccessType.READ));
        order.verifyNoMoreInteractions();
    }

    @Test
    void ignoringRowScope_stillMasksTheResponse() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new HashMap<>(Map.of("id", 1L, "employeeId", 2L)));

        wire(rows).searchListIgnoringRowScope(MODEL, queryAskingForAmount());

        verify(permissionService).maskResponseValue(eq(MODEL), eq(rows), eq(AccessType.READ));
    }

    /**
     * A sub-query onto a model with a row range of its own is read as a direct read would read it:
     * the plain sequence, range included.
     */
    @Test
    void relatedRows_ofAModelWithItsOwnRange_runTheWholeDirectReadSequence() {
        ModelServiceImpl<Long> svc = wire(new ArrayList<>());
        when(permissionService.hasOwnRowScope(MODEL)).thenReturn(true);

        svc.searchRelatedRows(MODEL, queryAskingForAmount());

        var order = inOrder(permissionService, jdbcService);
        order.verify(permissionService).hasOwnRowScope(MODEL);
        order.verify(permissionService).filterReadableFields(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).checkModelFieldsAccess(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).appendScopeAccessFilters(eq(MODEL), any(Filters.class));
        order.verify(jdbcService).selectByFilter(eq(MODEL), any(FlexQuery.class));
        order.verify(permissionService).maskResponseValue(eq(MODEL), any(), eq(AccessType.READ));
        order.verifyNoMoreInteractions();
    }

    /**
     * An owned child crosses the range — and only the range: the field guards and the response mask
     * stay on, as on every read.
     */
    @Test
    void relatedRows_ofAnOwnedChild_crossTheRangeWithTheFieldGuardsOn() {
        ModelServiceImpl<Long> svc = wire(new ArrayList<>());
        when(permissionService.hasOwnRowScope(MODEL)).thenReturn(false);
        FlexQuery query = queryAskingForAmount();

        svc.searchRelatedRows(MODEL, query);

        var order = inOrder(permissionService, jdbcService);
        order.verify(permissionService).hasOwnRowScope(MODEL);
        order.verify(permissionService).filterReadableFields(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).checkModelFieldsAccess(eq(MODEL), any(), eq(AccessType.READ));
        order.verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(MODEL), any(Filters.class));
        order.verify(jdbcService).selectByFilter(eq(MODEL), any(FlexQuery.class));
        order.verify(permissionService).maskResponseValue(eq(MODEL), any(), eq(AccessType.READ));
        order.verifyNoMoreInteractions();
        assertThat(query.getFields()).containsExactlyInAnyOrder("id", "employeeId");
    }
}
