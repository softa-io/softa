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
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        service = new PermissionServiceImpl(provider, null, null, modelService, applicability);
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

    /** Declare the child anchorless and reachable from Employee through {@code relation}. */
    private void employeeReferences(String child, FieldType relation, String fieldName, String relatedField) {
        when(applicability.applicableFor(child)).thenReturn(UNIVERSAL_ONLY);
        MetaField f = mock(MetaField.class);
        when(f.getRelatedModel()).thenReturn(child);
        when(f.getFieldType()).thenReturn(relation);
        when(f.getFieldName()).thenReturn(fieldName);
        when(f.getRelatedField()).thenReturn(relatedField);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(f));
    }

    /** checkIdsAccess under a bound context — without one, shouldBypass() waves everything through. */
    private void checkIds(String model, AccessType accessType) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        ContextHolder.runWith(ctx, () -> service.checkIdsAccess(model, List.of(1L), accessType));
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
        employeeReferences("EmpTimeProfile", FieldType.ONE_TO_ONE, "empTimeProfileId", null);
        when(modelService.getRelatedIds(eq("Employee"), any(Filters.class), eq("empTimeProfileId")))
                .thenReturn(List.of(7L, 8L));

        assertThat(scopeOf("EmpTimeProfile").toString()).contains("id", "7", "8");
    }

    @Test
    void oneToOneChildIsDeniedWhenTheOwnerSeesNothing() {
        employeeReferences("EmpTimeProfile", FieldType.ONE_TO_ONE, "empTimeProfileId", null);
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
        employeeReferences("EmpAddress", FieldType.ONE_TO_MANY, "empAddresses", "employeeId");
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(101L, 102L));

        assertThat(scopeOf("EmpAddress").toString()).contains("employeeId", "101", "102");
    }

    @Test
    void oneToManyChildIsDeniedWhenNoParentIsVisible() {
        employeeReferences("EmpAddress", FieldType.ONE_TO_MANY, "empAddresses", "employeeId");
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of());

        assertThat(scopeOf("EmpAddress")).isEqualTo(matchNoneAnded());
    }

    @Test
    void oneToManyWithoutABackReferenceFieldStaysFailClosed() {
        // relatedField names the child's FK column; with nothing to filter on there is no safe
        // narrowing to apply, so the child must not become readable by default.
        employeeReferences("EmpAddress", FieldType.ONE_TO_MANY, "empAddresses", null);

        assertThat(scopeOf("EmpAddress")).isEqualTo(matchNoneAnded());
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

    // ── bookkeeping the runtime writes for itself ──────────────────────────────────────────

    /**
     * A model nothing references and no rule can name — a file record, an import history row, a login
     * entry. On CREATE the ids were minted by this very call, so there is no earlier row to expose and
     * no rule that could ever put them in scope: the check cannot pass for any non-admin, which makes
     * it a wall rather than a control. Every file-producing and import feature died on it.
     */
    @Test
    void creatingAnAnchorlessBookkeepingRowIsAllowed() {
        when(applicability.applicableFor("ImportHistory")).thenReturn(UNIVERSAL_ONLY);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

        checkIds("ImportHistory", AccessType.CREATE);
    }

    /** Reading one by id is a different question, and still fails closed. */
    @Test
    void readingAnAnchorlessBookkeepingRowStillFailsClosed() {
        when(applicability.applicableFor("ImportHistory")).thenReturn(UNIVERSAL_ONLY);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

        assertThatThrownBy(() -> checkIds("ImportHistory", AccessType.READ))
                .isInstanceOf(io.softa.framework.base.exception.PermissionException.class);
    }

    // ── the anchored case is unchanged ─────────────────────────────────────────────────────

    @Test
    void aModelWithItsOwnAnchorStillFailsClosedWithoutAGrant() {
        // Real business data (it carries departmentId, so a scope rule could restrict it) is NOT
        // reachable through this fallback — it still requires an explicit grant.
        when(applicability.applicableFor("LeaveBalanceAccount"))
                .thenReturn(java.util.Set.of(ScopeType.ALL, ScopeType.CUSTOM,
                        ScopeType.CREATED_BY_SELF, ScopeType.DEPT_SUBTREE));

        assertThat(scopeOf("LeaveBalanceAccount")).isEqualTo(matchNoneAnded());
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
