package io.softa.starter.permission.index;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.permission.spi.PermissionEndpointSource;
import io.softa.starter.permission.spi.PermissionEndpointSource.PermissionEndpointDef;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standard-action endpoint derivation + L1/L2 lookup fan-out tests for
 * {@link EndpointIndex}. The existing {@link EndpointIndexTest} covers
 * explicit-endpoint paths + validation; this file covers the derivation
 * that fires when a permission has no explicit endpoints (model + action only).
 *
 * <p>2026-07-15: the navigation→model resolution moved to the
 * {@code PermissionEndpointSource} impl (user-starter); the def now carries the
 * resolved {@code model} directly, so these tests supply it verbatim.
 */
class EndpointIndexStandardDerivationTest {

    /** A no-explicit-endpoints permission def — triggers standard derivation
     *  from {@code model} + action (the id's last segment). */
    private static PermissionEndpointDef derivedPerm(String id, String model) {
        return new PermissionEndpointDef(id, null, model);
    }

    private static MetaField field(String name, FieldType type, String relatedModel) {
        MetaField mf = new MetaField();
        ReflectionTestUtils.setField(mf, "fieldName", name);
        ReflectionTestUtils.setField(mf, "fieldType", type);
        if (relatedModel != null) ReflectionTestUtils.setField(mf, "relatedModel", relatedModel);
        return mf;
    }

    private EndpointIndex build(List<PermissionEndpointDef> permissions) {
        PermissionEndpointSource source = () -> permissions;
        EndpointIndex idx = new EndpointIndex(source);
        idx.init();
        return idx;
    }

    // ─── standard action → endpoint set ───

    @Test
    void viewAction_registersFullViewEndpointSet() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.view", "Employee")));

            // A permission on `view` should register the whole read-endpoint set.
            assertThat(idx.lookup("/Employee/searchList", "POST")).containsExactly("employee.view");
            assertThat(idx.lookup("/Employee/searchPage", "POST")).containsExactly("employee.view");
            assertThat(idx.lookup("/Employee/getById", "POST")).containsExactly("employee.view");
            assertThat(idx.lookup("/Employee/getByIds", "POST")).containsExactly("employee.view");
            assertThat(idx.lookup("/Employee/searchName", "POST")).containsExactly("employee.view");
            assertThat(idx.lookup("/Employee/count", "POST")).containsExactly("employee.view");
            assertThat(idx.lookup("/Employee/getUnmaskedField", "GET")).containsExactly("employee.view");
        }
    }

    @Test
    void createAction_registersCreateEndpointSet() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.create", "Employee")));

            assertThat(idx.lookup("/Employee/createOne", "POST")).containsExactly("employee.create");
            assertThat(idx.lookup("/Employee/createList", "POST")).containsExactly("employee.create");
            assertThat(idx.lookup("/Employee/getDefaultValues", "GET")).containsExactly("employee.create");
        }
    }

    @Test
    void updateAction_includesOnChangePatternEndpoint() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.update", "Employee")));

            // onChange/{fieldName} is a pattern entry.
            assertThat(idx.lookup("/Employee/onChange/firstName", "POST"))
                    .containsExactly("employee.update");
            assertThat(idx.lookup("/Employee/updateOne", "POST")).containsExactly("employee.update");
        }
    }

    @Test
    void deleteAction_noLookupDerivation() {
        // delete / export / copy actions do NOT trigger L1 lookup derivation —
        // they only register their own endpoints.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee"))
                    .thenReturn(List.of(field("departmentId", FieldType.MANY_TO_ONE, "Department")));

            EndpointIndex idx = build(List.of(derivedPerm("employee.delete", "Employee")));

            // Delete registered.
            assertThat(idx.lookup("/Employee/deleteById", "POST"))
                    .containsExactly("employee.delete");
            // But Department read endpoints are NOT auto-included (no lookup derivation on delete).
            assertThat(idx.lookup("/Department/searchList", "POST")).isEmpty();
            assertThat(idx.lookup("/Department/getById", "POST")).isEmpty();
        }
    }

    @Test
    void timelineEndpoints_rideOnTheMatchingCrudAction() {
        // TimelineModelController's four endpoints have no action of their own:
        // addVersion(AndFetch) inserts a slice → create;
        // setEndDate rewrites a slice's end date → update;
        // deleteBySliceId → delete.
        // Left unmapped they fall to "Endpoint not registered" → 403 for every non-admin caller.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(
                    derivedPerm("employee.create", "Employee"),
                    derivedPerm("employee.update", "Employee"),
                    derivedPerm("employee.delete", "Employee")));

            assertThat(idx.lookup("/Employee/addVersion", "POST")).containsExactly("employee.create");
            assertThat(idx.lookup("/Employee/addVersionAndFetch", "POST")).containsExactly("employee.create");
            assertThat(idx.lookup("/Employee/setEndDate", "POST")).containsExactly("employee.update");
            assertThat(idx.lookup("/Employee/deleteBySliceId", "POST")).containsExactly("employee.delete");
        }
    }

    @Test
    void exportAction_registersSharedAbsoluteEndpoints() {
        // Export is served by shared file-starter controllers (model in a request
        // param), so `export` maps to ABSOLUTE endpoints emitted verbatim — NOT
        // the per-model /<Model>/exportList shape (a phantom endpoint that never
        // existed and left export uncovered → "Endpoint not registered" 403).
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.export", "Employee")));

            assertThat(idx.lookup("/export/dynamicExport", "POST")).containsExactly("employee.export");
            assertThat(idx.lookup("/export/countExportable", "POST")).containsExactly("employee.export");
            assertThat(idx.lookup("/export/exportByTemplate", "POST")).containsExactly("employee.export");
            assertThat(idx.lookup("/ExportTemplate/listByModel", "POST")).containsExactly("employee.export");
            assertThat(idx.lookup("/ExportHistory/myExportHistory", "POST")).containsExactly("employee.export");
            // Regression guard: the old phantom per-model endpoint must NOT register.
            assertThat(idx.lookup("/Employee/exportList", "POST")).isEmpty();
        }
    }

    @Test
    void importAction_registersSharedAbsoluteEndpoints() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.import", "Employee")));

            assertThat(idx.lookup("/import/dynamicImport", "POST")).containsExactly("employee.import");
            assertThat(idx.lookup("/import/importByTemplate", "POST")).containsExactly("employee.import");
            assertThat(idx.lookup("/import/validateImport", "POST")).containsExactly("employee.import");
            assertThat(idx.lookup("/ImportTemplate/listByModel", "POST")).containsExactly("employee.import");
            assertThat(idx.lookup("/ImportTemplate/getTemplateFile", "GET")).containsExactly("employee.import");
            assertThat(idx.lookup("/ImportHistory/myImportHistory", "POST")).containsExactly("employee.import");
            // Regression guard: the old phantom per-model endpoint must NOT register.
            assertThat(idx.lookup("/Employee/importList", "POST")).isEmpty();
        }
    }

    @Test
    void nonStandardAction_refusesToRegister() {
        // e.g. `employee.confirmation` — not in STANDARD_ACTION_MAP → log ERROR
        // and register nothing so the permission stays visibly inert.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.confirmation", "Employee")));

            // The custom action mapped nowhere — no endpoint registered.
            assertThat(idx.lookup("/Employee/confirmation", "POST")).isEmpty();
        }
    }

    @Test
    void modelMissing_permissionIneffective() {
        // model unresolved (source handed a null model) → deriveStandardEndpoints
        // returns empty.
        EndpointIndex idx = build(List.of(derivedPerm("ghost.view", null)));
        assertThat(idx.lookup("/Ghost/searchList", "POST")).isEmpty();
    }

    // ─── L1 lookup derivation ───

    @Test
    void viewOnEmployee_derivesL1DepartmentViewEndpoints() {
        // Employee has manyToOne to Department → employee.view implicitly
        // authorises Department read endpoints (department-tree side panel etc.).
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("departmentId", FieldType.MANY_TO_ONE, "Department")));
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(derivedPerm("employee.view", "Employee")));

            // Employee endpoints, direct.
            assertThat(idx.lookup("/Employee/searchList", "POST"))
                    .containsExactly("employee.view");
            // Department gets the full view set via L1.
            assertThat(idx.lookup("/Department/searchList", "POST"))
                    .containsExactly("employee.view");
            assertThat(idx.lookup("/Department/getById", "POST"))
                    .containsExactly("employee.view");
        }
    }

    @Test
    void viewOnEmployee_derivesL2NestedLookupPickerSubset() {
        // Employee → Department → LegalEntity chain. L1 gives Department full view,
        // L2 gives LegalEntity only the picker subset (searchName + getById/getByIds).
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("LegalEntity")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("departmentId", FieldType.MANY_TO_ONE, "Department")));
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of(
                    field("legalEntityId", FieldType.MANY_TO_ONE, "LegalEntity")));

            EndpointIndex idx = build(List.of(derivedPerm("employee.view", "Employee")));

            // L1 Department gets full view.
            assertThat(idx.lookup("/Department/searchList", "POST"))
                    .containsExactly("employee.view");
            // L2 LegalEntity gets picker subset only.
            assertThat(idx.lookup("/LegalEntity/searchName", "POST"))
                    .containsExactly("employee.view");
            assertThat(idx.lookup("/LegalEntity/getById", "POST"))
                    .containsExactly("employee.view");
            // But NOT the full view.
            assertThat(idx.lookup("/LegalEntity/searchList", "POST")).isEmpty();
            assertThat(idx.lookup("/LegalEntity/searchPage", "POST")).isEmpty();
        }
    }

    @Test
    void lookupDerivation_cycleProtection() {
        // Model A → B → A cycle. Visited set must prevent infinite loop.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("ModelA")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("ModelB")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("ModelA")).thenReturn(List.of(
                    field("bId", FieldType.MANY_TO_ONE, "ModelB")));
            mm.when(() -> ModelManager.getModelFields("ModelB")).thenReturn(List.of(
                    field("aId", FieldType.MANY_TO_ONE, "ModelA")));

            EndpointIndex idx = build(List.of(derivedPerm("a.view", "ModelA")));

            // Own model + related B — both reachable, cycle back to A drops.
            assertThat(idx.lookup("/ModelA/searchList", "POST")).containsExactly("a.view");
            assertThat(idx.lookup("/ModelB/searchList", "POST")).containsExactly("a.view");
        }
    }

    @Test
    void multiplePermissionsShareLookupTarget() {
        // Both employee.view and department.view claim /Department/searchList
        // (former via L1 derivation, latter directly).
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("departmentId", FieldType.MANY_TO_ONE, "Department")));
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of());

            EndpointIndex idx = build(List.of(
                    derivedPerm("employee.view", "Employee"),
                    derivedPerm("department.view", "Department")));

            assertThat(idx.lookup("/Department/searchList", "POST"))
                    .containsExactlyInAnyOrder("employee.view", "department.view");
        }
    }

    // ─── Non-relational fields don't trigger derivation ───

    @Test
    void primitiveField_noLookupDerived() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(
                    new ArrayList<>(List.of(field("firstName", FieldType.STRING, null))));

            EndpointIndex idx = build(List.of(derivedPerm("employee.view", "Employee")));

            // No related model → nothing derived beyond Employee's own endpoints.
            assertThat(idx.lookup("/Department/searchList", "POST")).isEmpty();
        }
    }
}
