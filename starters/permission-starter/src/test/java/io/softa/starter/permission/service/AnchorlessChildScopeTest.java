package io.softa.starter.permission.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Row-scope for a child model the caller was never granted directly — the case the role wizard
 * relies on but nothing covered.
 *
 * <p>{@code NavigationConfigOptionsController} deliberately does not offer OneToOne / OneToMany
 * children as separately grantable models, on the stated grounds that they are "bounded by the
 * parent's scope". Nothing enforced that at runtime: {@code hasForwardAnchor} counted the scope
 * types every model trivially supports (ALL / CUSTOM are {@code appliesToAll}; CREATED_BY_SELF
 * keys on {@code createdId}, which {@code AuditableModel} puts on every table), so it answered
 * true for every model and the follow-the-owner fallback underneath it was unreachable. Every
 * such child fail-closed to zero rows instead — a leave request's detail page 403'd on
 * {@code EmpTimeProfile} despite the role holding {@code Employee = ALL}.
 *
 * <p>The two owned kinds are asserted separately because their foreign key points the opposite
 * way, so one filter shape cannot serve both: for ONE_TO_ONE the FK is on the parent and holds
 * the child's id (filter the child's {@code id}); for ONE_TO_MANY it is on the child and holds
 * the parent's id (filter that back-reference column).
 */
class AnchorlessChildScopeTest {

    private static final Long TENANT = 1L;
    private static final Long USER = 42L;

    /** What a model with no anchor of its own resolves to — the universal types, and only those. */
    private static final java.util.Set<ScopeType> UNIVERSAL_ONLY =
            java.util.Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF);

    private MockedStatic<ModelManager> modelManager;
    private ModelService<Long> modelService;
    private ScopeApplicabilityResolver applicability;
    private PermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelService = mockModelService();
        applicability = mock(ScopeApplicabilityResolver.class);

        PermissionInfo pi = new PermissionInfo();
        // The caller holds a grant on the PARENT only — never on the child under test.
        pi.setModelScopeMap(Map.of("Employee", List.of(rule(ScopeType.ALL))));
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);

        // The owner's rows are compiled from the same grant now, rather than re-read through the
        // model service; the parent's ALL rule compiles to "no restriction", which is null.
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        service = new PermissionServiceImpl(provider, compiler, null, modelService, applicability);
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> mockModelService() {
        return mock(ModelService.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private static ScopeRule rule(ScopeType type) {
        ScopeRule r = new ScopeRule();
        r.setScopeType(type);
        return r;
    }

    /**
     * What a one-to-many child really resolves to. A child of that cardinality ALWAYS carries the
     * parent's foreign key — that is what makes it one-to-many, the parent cannot hold N ids — and
     * {@code SELF}'s filter is {@code ["employeeId", "=", "USER_EMP_ID"]}, so {@code SELF} is
     * applicable to every one of them. {@link #UNIVERSAL_ONLY} is therefore a shape no such child
     * can have, and stubbing it that way pinned a premise that does not occur in production.
     *
     * <p>A one-to-one child is the opposite case and keeps {@code UNIVERSAL_ONLY}: its FK lives on
     * the parent ({@code employee.emp_time_profile_id}), so it has no back-reference column and no
     * anchor of its own. That asymmetry is why the fallback looked correct — the only shape that
     * ever reached it was the only shape the bug could not affect.
     */
    private static final java.util.Set<ScopeType> CARRIES_EMPLOYEE_ID =
            java.util.Set.of(ScopeType.ALL, ScopeType.SELF, ScopeType.CREATED_BY_SELF);

    /** Declare the child reachable from Employee through {@code relation}, with its real anchor set. */
    private void employeeReferences(String child, FieldType relation, String fieldName,
                                    String relatedField, java.util.Set<ScopeType> applicable) {
        when(applicability.applicableFor(child)).thenReturn(applicable);
        MetaField f = mock(MetaField.class);
        when(f.getRelatedModel()).thenReturn(child);
        when(f.getFieldType()).thenReturn(relation);
        when(f.getFieldName()).thenReturn(fieldName);
        when(f.getRelatedField()).thenReturn(relatedField);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(f));
    }

    private Filters scopeOf(String model) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        return ContextHolder.callWith(ctx, () -> service.appendScopeAccessFilters(model, new Filters()));
    }

    // ── ONE_TO_ONE: FK on the parent, holding the child's id ───────────────────────────────

    @Test
    void oneToOneChildIsScopedByTheOwnersVisibleForeignKeys() {
        // Employee.empTimeProfileId -> EmpTimeProfile. The visible children are the FK values of
        // the owner rows the caller can see, so the filter lands on the child's own id.
        employeeReferences("EmpTimeProfile", FieldType.ONE_TO_ONE, "empTimeProfileId", null, UNIVERSAL_ONLY);
        when(modelService.getRelatedIds(eq("Employee"), any(Filters.class), eq("empTimeProfileId")))
                .thenReturn(List.of(7L, 8L));

        assertThat(scopeOf("EmpTimeProfile").toString()).contains("id", "7", "8");
    }

    @Test
    void oneToOneChildIsDeniedWhenTheOwnerSeesNothing() {
        employeeReferences("EmpTimeProfile", FieldType.ONE_TO_ONE, "empTimeProfileId", null, UNIVERSAL_ONLY);
        when(modelService.getRelatedIds(eq("Employee"), any(Filters.class), eq("empTimeProfileId")))
                .thenReturn(List.of());

        // Parent strict => child strict. An empty owner set must not read as "unrestricted".
        assertThat(scopeOf("EmpTimeProfile")).isEqualTo(matchNoneAnded());
    }

    // ── ONE_TO_MANY: FK on the child, holding the parent's id (inverse direction) ───────────

    @Test
    void oneToManyChildIsScopedByItsBackReferenceToVisibleParents() {
        // Employee.empAddresses -> EmpAddress, back-referenced by EmpAddress.employeeId. Filtering
        // the child's id here would be wrong — the ids in hand are the PARENT's.
        employeeReferences("EmpAddress", FieldType.ONE_TO_MANY, "empAddresses", "employeeId", CARRIES_EMPLOYEE_ID);
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(101L, 102L));

        assertThat(scopeOf("EmpAddress").toString()).contains("employeeId", "101", "102");
    }

    @Test
    void oneToManyChildIsDeniedWhenNoParentIsVisible() {
        employeeReferences("EmpAddress", FieldType.ONE_TO_MANY, "empAddresses", "employeeId", CARRIES_EMPLOYEE_ID);
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of());

        assertThat(scopeOf("EmpAddress")).isEqualTo(matchNoneAnded());
    }

    @Test
    void oneToManyWithoutABackReferenceFieldStaysFailClosed() {
        // relatedField names the child's FK column; with nothing to filter on there is no safe
        // narrowing to apply, so the child must not become readable by default.
        employeeReferences("EmpAddress", FieldType.ONE_TO_MANY, "empAddresses", null, CARRIES_EMPLOYEE_ID);

        assertThat(scopeOf("EmpAddress")).isEqualTo(matchNoneAnded());
    }

    @Test
    void oneToOneChildCarryingARedundantEmployeeIdIsStillScopedByItsOwner() {
        // EmpBankAccount is owned one-to-one (Employee.empBankAccountId) yet also carries a
        // back-reference of its own, so it has an anchor without being anchored-by-design. The
        // cardinality is what decides ownership, not whether the column happens to be there.
        employeeReferences("EmpBankAccount", FieldType.ONE_TO_ONE, "empBankAccountId", null,
                CARRIES_EMPLOYEE_ID);
        when(modelService.getRelatedIds(eq("Employee"), any(Filters.class), eq("empBankAccountId")))
                .thenReturn(List.of(9L));

        assertThat(scopeOf("EmpBankAccount").toString()).contains("id", "9");
    }

    // ── shared master two hops out: granted → owned child → master ─────────────────────────

    /** A relation field of {@code owner} pointing at {@code target}. */
    private static MetaField relation(String target, FieldType type, String fieldName) {
        MetaField f = mock(MetaField.class);
        when(f.getRelatedModel()).thenReturn(target);
        when(f.getFieldType()).thenReturn(type);
        when(f.getFieldName()).thenReturn(fieldName);
        return f;
    }

    private void modelFields(String model, MetaField... fields) {
        modelManager.when(() -> ModelManager.existModel(model)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields(model)).thenReturn(List.of(fields));
    }

    @Test
    void aSharedMasterReferencedFromAnOwnedChildIsReadable() {
        // Employee.empTimeProfileId -> EmpTimeProfile.attendanceGroupId -> AttendanceGroup. The child
        // is never granted in its own right (the wizard does not offer owned children), so a
        // single-hop scan misses the master and the picker reads "No options available" — while the
        // endpoint layer, which derives lookups two layers deep, has already let the call through.
        when(applicability.applicableFor("AttendanceGroup")).thenReturn(UNIVERSAL_ONLY);
        modelFields("Employee", relation("EmpTimeProfile", FieldType.ONE_TO_ONE, "empTimeProfileId"));
        modelFields("EmpTimeProfile",
                relation("AttendanceGroup", FieldType.MANY_TO_ONE, "attendanceGroupId"));

        assertThat(scopeOf("AttendanceGroup")).isEqualTo(new Filters());
    }

    @Test
    void theSecondHopFollowsOwnedChildrenOnlyNotSharedReferences() {
        // Employee.departmentId -> Department is a shared reference, not something Employee owns.
        // Walking through it would make every master any reference of a granted model happens to
        // point at readable, which is a different (and much wider) claim than "owned by a row I see".
        when(applicability.applicableFor("CostCentre")).thenReturn(UNIVERSAL_ONLY);
        modelFields("Employee", relation("Department", FieldType.MANY_TO_ONE, "departmentId"));
        modelFields("Department", relation("CostCentre", FieldType.MANY_TO_ONE, "costCentreId"));

        assertThat(scopeOf("CostCentre")).isEqualTo(matchNoneAnded());
    }

    @Test
    void aDirectReferenceStillWinsOverTheSecondHop() {
        // Reachable both ways — the one-hop answer is the more precise statement, so the extra hop
        // must never pre-empt it.
        when(applicability.applicableFor("AttendanceGroup")).thenReturn(UNIVERSAL_ONLY);
        modelFields("Employee",
                relation("EmpTimeProfile", FieldType.ONE_TO_ONE, "empTimeProfileId"),
                relation("AttendanceGroup", FieldType.ONE_TO_ONE, "attendanceGroupId"));
        modelFields("EmpTimeProfile",
                relation("AttendanceGroup", FieldType.MANY_TO_ONE, "attendanceGroupId"));
        when(modelService.getRelatedIds(eq("Employee"), any(Filters.class), eq("attendanceGroupId")))
                .thenReturn(List.of(7L));

        assertThat(scopeOf("AttendanceGroup").toString()).contains("id", "7");
    }

    // ── unreachable ────────────────────────────────────────────────────────────────────────

    @Test
    void anAnchorlessModelNoGrantedModelReferencesStaysFailClosed() {
        when(applicability.applicableFor("Orphan")).thenReturn(UNIVERSAL_ONLY);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

        assertThat(scopeOf("Orphan")).isEqualTo(matchNoneAnded());
    }

    // ── the anchored case is unchanged ─────────────────────────────────────────────────────

    /**
     * The guard on the reordering: only OWNERSHIP edges are resolved ahead of the anchor test.
     * A model that a granted model merely REFERENCES stays behind it, so anchored business data
     * cannot become readable just because something points at it — which is what would happen if
     * SHARED jumped the queue along with the two owned kinds.
     */
    @Test
    void aSharedReferenceDoesNotOvertakeTheAnchorTest() {
        when(applicability.applicableFor("LeaveBalanceAccount"))
                .thenReturn(java.util.Set.of(ScopeType.ALL, ScopeType.CREATED_BY_SELF,
                        ScopeType.DEPT_SUBTREE));
        modelFields("Employee",
                relation("LeaveBalanceAccount", FieldType.MANY_TO_ONE, "leaveBalanceAccountId"));

        assertThat(scopeOf("LeaveBalanceAccount")).isEqualTo(matchNoneAnded());
    }

    @Test
    void aModelWithItsOwnAnchorStillFailsClosedWithoutAGrant() {
        // Real business data (it carries departmentId, so a scope rule could restrict it) is NOT
        // reachable through this fallback — it still requires an explicit grant.
        when(applicability.applicableFor("LeaveBalanceAccount"))
                .thenReturn(java.util.Set.of(ScopeType.ALL, ScopeType.CUSTOM,
                        ScopeType.CREATED_BY_SELF, ScopeType.DEPT_SUBTREE));

        assertThat(scopeOf("LeaveBalanceAccount")).isEqualTo(matchNoneAnded());
    }

    // ── the id list is de-duplicated before it is counted ──────────────────────────────────

    /**
     * SQL's {@code IN} de-duplicates, so comparing a COUNT against the raw list size false-rejects
     * a legitimate call: {@code deleteByIds(model, [7, 7])} counted 1 against a size of 2. The
     * caller was expected to de-duplicate — {@code RequirePermissionAspect} does, and the owned
     * branch of this very method used a {@code distinct()} count while the generic one did not.
     * One yardstick, established at the entry.
     */
    @Test
    void repeatedIdsAreNotMistakenForOutOfScopeOnes() {
        // Employee carries an explicit grant in the fixture, so this goes straight to the generic
        // count — the path where the two comparisons disagreed.
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L);

        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        ContextHolder.runWith(ctx, () -> service.checkIdsAccess(
                "Employee", List.of(7L, 7L), io.softa.framework.orm.enums.AccessType.DELETE));
    }

    /** What {@code combineAnd(new Filters(), matchNone())} produces. */
    private Filters matchNoneAnded() {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        when(applicability.applicableFor("__denied__"))
                .thenReturn(java.util.Set.of(ScopeType.ALL, ScopeType.DEPT_SUBTREE));
        return ContextHolder.callWith(ctx,
                () -> service.appendScopeAccessFilters("__denied__", new Filters()));
    }
}
