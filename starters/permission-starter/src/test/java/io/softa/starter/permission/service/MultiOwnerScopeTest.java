package io.softa.starter.permission.service;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.OnDelete;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An anchorless child pointed at one-to-one by more than one granted model.
 *
 * <p>A person's profile is owned by the employee record ({@code onDelete = RESTRICT}) and also pointed
 * at by report rows built over it ({@code onDelete = CASCADE}: the report row goes when the profile
 * goes). A role reading its own department's employees and the report in full must see the profiles
 * of its own employees only — and must get that answer whichever order its grant holds the two models
 * in, which is how the first owner found used to decide it.
 */
class MultiOwnerScopeTest {

    private static final Long TENANT = 1L;
    private static final Long USER = 42L;
    private static final String CHILD = "EmployeeProfile";
    private static final Filters DEPARTMENT_RANGE = Filters.of("departmentId", Operator.EQUAL, 5L);

    private MockedStatic<ModelManager> modelManager;
    private ModelService<Long> modelService;
    private ScopeApplicabilityResolver applicability;
    private ScopeRuleCompiler compiler;
    private PermissionSnapshotProvider provider;
    private PermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelService = mockModelService();
        applicability = mock(ScopeApplicabilityResolver.class);
        // The profile carries no anchor of its own.
        when(applicability.applicableFor(CHILD)).thenReturn(
                java.util.Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF));
        compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), eq("Employee"))).thenReturn(DEPARTMENT_RANGE);
        // ALL compiles to "no restriction".
        when(compiler.compile(anyList(), eq("BirthdayCountdown"))).thenReturn(null);
        when(compiler.compile(anyList(), eq("ConsultantAssignment"))).thenReturn(null);
        provider = mock(PermissionSnapshotProvider.class);
        service = new PermissionServiceImpl(provider, compiler, null, modelService, applicability);

        // The employee's own profile, and everyone's through the report.
        when(modelService.getRelatedIds(eq("Employee"), any(Filters.class), eq("employeeProfileId")))
                .thenReturn(List.of(7L));
        when(modelService.getRelatedIds(eq("BirthdayCountdown"), any(Filters.class), eq("employeeProfileId")))
                .thenReturn(List.of(7L, 8L, 9L));
        when(modelService.getRelatedIds(eq("ConsultantAssignment"), any(Filters.class), eq("profileId")))
                .thenReturn(List.of(20L));
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> mockModelService() {
        return mock(ModelService.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aReportOverTheProfileDoesNotOpenItWhicheverOrderTheGrantHolds() {
        declare("Employee", oneToOne("employeeProfileId", OnDelete.RESTRICT));
        declare("BirthdayCountdown", oneToOne("employeeProfileId", OnDelete.CASCADE));

        for (List<String> order : List.of(List.of("BirthdayCountdown", "Employee"), List.of("Employee", "BirthdayCountdown"))) {
            grant(order);
            String scope = scopeOf(CHILD).toString();
            assertThat(scope).as("grant order %s", order).contains("7").doesNotContain("8", "9");
        }
    }

    @Test
    void aProfileOutOfRangeCannotBeNamedByIdForAnUpdateWhicheverOrderTheGrantHolds() {
        declare("Employee", oneToOne("employeeProfileId", OnDelete.RESTRICT));
        declare("BirthdayCountdown", oneToOne("employeeProfileId", OnDelete.CASCADE));
        when(modelService.count(eq(CHILD), any(Filters.class))).thenReturn(0L);

        for (List<String> order : List.of(List.of("BirthdayCountdown", "Employee"), List.of("Employee", "BirthdayCountdown"))) {
            Mockito.clearInvocations(modelService);
            grant(order);
            assertThatThrownBy(() -> asCaller(() -> {
                service.checkIdsAccess(CHILD, List.<Serializable>of(8L), AccessType.UPDATE);
                return null;
            })).as("grant order %s", order).isNotNull();

            ArgumentCaptor<Filters> checked = ArgumentCaptor.forClass(Filters.class);
            verify(modelService).count(eq(CHILD), checked.capture());
            assertThat(checked.getValue().toString()).as("grant order %s", order).contains("7").doesNotContain("9");
        }
    }

    @Test
    void twoOwnersOfTheirOwnBothOpenTheirProfiles() {
        declare("Employee", oneToOne("employeeProfileId", OnDelete.RESTRICT));
        declare("ConsultantAssignment", oneToOne("profileId", null));

        for (List<String> order : List.of(List.of("ConsultantAssignment", "Employee"), List.of("Employee", "ConsultantAssignment"))) {
            grant(order);
            assertThat(scopeOf(CHILD).toString()).as("grant order %s", order).contains("7", "20");
        }
    }

    @Test
    void aReportAloneDoesNotOpenTheProfile() {
        declare("BirthdayCountdown", oneToOne("employeeProfileId", OnDelete.CASCADE));
        grant(List.of("BirthdayCountdown"));

        assertThat(scopeOf(CHILD).toString()).doesNotContain("7", "8", "9");
    }

    private MetaField oneToOne(String fieldName, OnDelete onDelete) {
        MetaField f = mock(MetaField.class);
        when(f.getRelatedModel()).thenReturn(CHILD);
        when(f.getFieldType()).thenReturn(FieldType.ONE_TO_ONE);
        when(f.getFieldName()).thenReturn(fieldName);
        when(f.getOnDelete()).thenReturn(onDelete);
        return f;
    }

    private void declare(String owner, MetaField field) {
        modelManager.when(() -> ModelManager.existModel(owner)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields(owner)).thenReturn(List.of(field));
    }

    /** Grants the models in exactly this iteration order; Employee is range-bound, the rest in full. */
    private void grant(List<String> models) {
        Map<String, List<ScopeRule>> scopes = new LinkedHashMap<>();
        for (String model : models) {
            ScopeRule rule = new ScopeRule();
            rule.setScopeType("Employee".equals(model) ? ScopeType.CUSTOM : ScopeType.ALL);
            scopes.put(model, List.of(rule));
        }
        PermissionInfo pi = new PermissionInfo();
        pi.setModelScopeMap(scopes);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);
    }

    private Filters scopeOf(String model) {
        return asCaller(() -> service.appendScopeAccessFilters(model, new Filters()));
    }

    private static <T> T asCaller(java.util.function.Supplier<T> action) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        return ContextHolder.callWith(ctx, action::get);
    }
}
