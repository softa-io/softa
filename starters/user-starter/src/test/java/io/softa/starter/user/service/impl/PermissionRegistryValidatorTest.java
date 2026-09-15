package io.softa.starter.user.service.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Permission;
import io.softa.starter.user.entity.SensitiveFieldSet;
import io.softa.starter.user.enums.NavigationType;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRegistryValidatorTest {

    // Fixtures ─────────────────────────────────────────────────

    private static Permission perm(String id, String navId) {
        Permission p = new Permission();
        p.setId(id);
        p.setNavigationId(navId);
        return p;
    }

    private static Navigation nav(String id, NavigationType type, String model) {
        Navigation n = new Navigation();
        n.setId(id);
        n.setType(type);
        n.setModel(model);
        return n;
    }

    private static Navigation navWithParent(String id, NavigationType type, String model, String parentId) {
        Navigation n = nav(id, type, model);
        n.setParentId(parentId);
        return n;
    }

    private static SensitiveFieldSet sfs(String id, String model, List<String> codes) {
        SensitiveFieldSet s = new SensitiveFieldSet();
        s.setId(id);
        s.setModel(model);
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        codes.forEach(arr::add);
        s.setFieldCodes(arr);
        return s;
    }

    // Reflection helpers ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, String> callCheckPermissionUniqueness(List<Permission> perms, List<String> errors) throws Exception {
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkPermissionUniqueness", List.class, List.class);
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(null, perms, errors);
    }

    private static void callCheckNavigationRows(List<Navigation> navs, Map<String, Navigation> navById, List<String> errors) throws Exception {
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkNavigationRows", List.class, Map.class, List.class);
        m.setAccessible(true);
        m.invoke(null, navs, navById, errors);
    }

    private static void callCheckSensitiveFieldSetRows(List<SensitiveFieldSet> rows, List<String> errors) throws Exception {
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkSensitiveFieldSetRows", List.class, List.class);
        m.setAccessible(true);
        m.invoke(null, rows, errors);
    }

    // ─── Rule ②: Permission.id uniqueness ───

    @Test
    void checkPermissionUniqueness_uniqueIds_noError() throws Exception {
        List<String> errors = new ArrayList<>();
        Map<String, String> permNav = callCheckPermissionUniqueness(
                List.of(perm("employee.view", "hr.employee"),
                        perm("employee.create", "hr.employee")), errors);
        assertThat(errors).isEmpty();
        assertThat(permNav)
                .containsEntry("employee.view", "hr.employee")
                .containsEntry("employee.create", "hr.employee");
    }

    @Test
    void checkPermissionUniqueness_duplicateId_reportsError() throws Exception {
        List<String> errors = new ArrayList<>();
        callCheckPermissionUniqueness(
                List.of(perm("employee.view", "hr.employee"),
                        perm("employee.view", "some.other")), errors);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("employee.view").contains("duplicated");
    }

    @Test
    void checkPermissionUniqueness_nullId_skippedNotReported() throws Exception {
        List<String> errors = new ArrayList<>();
        callCheckPermissionUniqueness(
                List.of(perm(null, "hr.employee"),
                        perm("employee.view", "hr.employee")), errors);
        assertThat(errors).isEmpty();
    }

    @Test
    void checkPermissionUniqueness_nullNavigationId_reportsError() throws Exception {
        // Both admin snapshots build their permission set from the navigations they may reach, so a
        // permission naming none lands in nobody's set — and because the gate treats a REGISTERED
        // endpoint as gated, its endpoints stop answering for the platform administrator and the
        // tenant administrator alike. Silently: the row looks fine and the endpoints are indexed.
        List<String> errors = new ArrayList<>();
        callCheckPermissionUniqueness(List.of(perm("employee.view", null)), errors);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("employee.view").contains("navigationId is null");
    }

    @Test
    void checkPermissionUniqueness_nullIdAndNullNav_reportsNeither() throws Exception {
        // The paired case: the id check skips a row with no id, and the nav check must skip it too —
        // an unidentifiable row cannot be reported usefully, and reporting it twice from one row
        // would make a single bad seed look like two.
        List<String> errors = new ArrayList<>();
        callCheckPermissionUniqueness(List.of(perm(null, null)), errors);
        assertThat(errors).isEmpty();
    }

    // ─── Rule ⑤: nav parent/child type compatibility ───

    @Test
    void checkNavigationRows_menuChildOfGroup_allowed() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            List<Navigation> navs = List.of(
                    nav("root", NavigationType.GROUP, null),
                    navWithParent("hr.employee", NavigationType.MENU, "Employee", "root"));
            Map<String, Navigation> byId = index(navs);
            List<String> errors = new ArrayList<>();

            callCheckNavigationRows(navs, byId, errors);
            assertThat(errors).isEmpty();
        }
    }

    @Test
    void checkNavigationRows_menuChildOfButton_forbidden() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            List<Navigation> navs = List.of(
                    nav("btn", NavigationType.BUTTON, "Employee"),
                    navWithParent("child.menu", NavigationType.MENU, "Employee", "btn"));
            Map<String, Navigation> byId = index(navs);
            List<String> errors = new ArrayList<>();

            callCheckNavigationRows(navs, byId, errors);

            assertThat(errors)
                    .anyMatch(e -> e.contains("cannot be a child of parent"));
        }
    }

    @Test
    void checkNavigationRows_missingParent_reportsFkError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            List<Navigation> navs = List.of(
                    navWithParent("hr.employee", NavigationType.MENU, "Employee", "missing.parent"));
            Map<String, Navigation> byId = index(navs);
            List<String> errors = new ArrayList<>();

            callCheckNavigationRows(navs, byId, errors);
            assertThat(errors).anyMatch(e -> e.contains("references missing Navigation"));
        }
    }

    // ─── Rule ⑧: model constraints per nav type ───

    @Test
    void checkNavigationRows_buttonWithoutModel_reportsError() throws Exception {
        List<Navigation> navs = List.of(nav("hr.action", NavigationType.BUTTON, null));
        Map<String, Navigation> byId = index(navs);
        List<String> errors = new ArrayList<>();

        callCheckNavigationRows(navs, byId, errors);

        assertThat(errors).anyMatch(e -> e.contains("requires a non-null model"));
    }

    @Test
    void checkNavigationRows_groupWithModel_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            List<Navigation> navs = List.of(nav("hr.group", NavigationType.GROUP, "Employee"));
            Map<String, Navigation> byId = index(navs);
            List<String> errors = new ArrayList<>();

            callCheckNavigationRows(navs, byId, errors);
            assertThat(errors).anyMatch(e -> e.contains("must NOT set model"));
        }
    }

    @Test
    void checkNavigationRows_unknownModel_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Unknown")).thenReturn(false);
            List<Navigation> navs = List.of(nav("hr.foo", NavigationType.MENU, "Unknown"));
            Map<String, Navigation> byId = index(navs);
            List<String> errors = new ArrayList<>();

            callCheckNavigationRows(navs, byId, errors);
            assertThat(errors).anyMatch(e -> e.contains("not registered in ModelManager"));
        }
    }

    // ─── Rules ⑥⑦: SFS row integrity ───

    @Test
    void checkSensitiveFieldSetRows_nullModel_reportsError() throws Exception {
        SensitiveFieldSet s = new SensitiveFieldSet();
        s.setId("bad");
        s.setModel(null);
        List<String> errors = new ArrayList<>();
        callCheckSensitiveFieldSetRows(List.of(s), errors);
        assertThat(errors).anyMatch(e -> e.contains("null/empty model"));
    }

    @Test
    void checkSensitiveFieldSetRows_modelNotRegistered_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Ghost")).thenReturn(false);
            List<String> errors = new ArrayList<>();
            callCheckSensitiveFieldSetRows(
                    List.of(sfs("ghost-fields", "Ghost", List.of("f"))), errors);
            assertThat(errors).anyMatch(e -> e.contains("not registered in ModelManager"));
        }
    }

    @Test
    void checkSensitiveFieldSetRows_unknownField_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Employee", "salary")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Employee", "typo")).thenReturn(false);

            List<String> errors = new ArrayList<>();
            callCheckSensitiveFieldSetRows(
                    List.of(sfs("comp", "Employee", List.of("salary", "typo"))), errors);
            assertThat(errors).anyMatch(e ->
                    e.contains("references missing field 'typo'") && e.contains("Employee"));
        }
    }

    // ─── Rule ⑪ — SFS.attachedTo integrity ───

    @Test
    void checkSensitiveFieldSetRows_attachedToUnknownModel_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("EmpBankAccount")).thenReturn(true);
            mm.when(() -> ModelManager.existField(eqAny(), eqAny())).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Ghost")).thenReturn(false);

            SensitiveFieldSet s = new SensitiveFieldSet();
            s.setId("bank");
            s.setModel("EmpBankAccount");
            s.setFieldCodes(JsonNodeFactory.instance.arrayNode().add("acct"));
            ArrayNode attached = JsonNodeFactory.instance.arrayNode();
            attached.add("Ghost");
            s.setAttachedTo(attached);

            List<String> errors = new ArrayList<>();
            callCheckSensitiveFieldSetRows(List.of(s), errors);
            assertThat(errors).anyMatch(e ->
                    e.contains("attachedTo references unknown MetaModel 'Ghost'"));
        }
    }

    @Test
    void checkSensitiveFieldSetRows_attachedToDuplicatesOwnModel_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existField(eqAny(), eqAny())).thenReturn(true);

            SensitiveFieldSet s = new SensitiveFieldSet();
            s.setId("comp");
            s.setModel("Employee");
            s.setFieldCodes(JsonNodeFactory.instance.arrayNode().add("salary"));
            ArrayNode attached = JsonNodeFactory.instance.arrayNode();
            attached.add("Employee");   // redundant with `model`
            s.setAttachedTo(attached);

            List<String> errors = new ArrayList<>();
            callCheckSensitiveFieldSetRows(List.of(s), errors);
            assertThat(errors).anyMatch(e ->
                    e.contains("duplicates its own `model`"));
        }
    }

    // ─── Rules ③⑩ — RoleNavigation integrity ───

    @Test
    void checkRoleNavigationRows_navMissing_reportsError() throws Exception {
        List<String> errors = runRoleNavCheck(
                List.of(rn(1L, 100L, "missing.nav", null)),
                Map.of(),
                Map.of(),
                java.util.Set.of());

        assertThat(errors).anyMatch(e -> e.contains("references missing Navigation"));
    }

    @Test
    void checkRoleNavigationRows_nonGrantableNavType_reportsError() throws Exception {
        // Grant on a GROUP nav — GROUP isn't grantable.
        Navigation group = nav("hr", NavigationType.GROUP, null);
        List<String> errors = runRoleNavCheck(
                List.of(rn(1L, 100L, "hr", null)),
                Map.of("hr", group),
                Map.of(),
                java.util.Set.of());

        assertThat(errors).anyMatch(e ->
                e.contains("non-grantable nav") && e.contains("type=GROUP"));
    }

    @Test
    void checkRoleNavigationRows_navWithNullModel_reportsError() throws Exception {
        Navigation containerMenu = nav("hr.menu", NavigationType.MENU, null);   // model=null
        List<String> errors = runRoleNavCheck(
                List.of(rn(1L, 100L, "hr.menu", null)),
                Map.of("hr.menu", containerMenu),
                Map.of(),
                java.util.Set.of());

        assertThat(errors).anyMatch(e -> e.contains("null model"));
    }

    @Test
    void checkRoleNavigationRows_permissionFromDifferentNav_reportsError() throws Exception {
        // Grant on hr.employee references permission "leave.view" mounted at hr.leave —
        // rule ③ rejects cross-nav grants.
        Navigation empNav = nav("hr.employee", NavigationType.MENU, "Employee");
        Map<String, Navigation> byId = Map.of("hr.employee", empNav,
                "hr.leave", nav("hr.leave", NavigationType.MENU, "LeaveRequest"));
        Map<String, String> permNav = Map.of("leave.view", "hr.leave");

        ArrayNode permIds = JsonNodeFactory.instance.arrayNode();
        permIds.add("leave.view");
        List<String> errors = runRoleNavCheck(
                List.of(rn(1L, 100L, "hr.employee", permIds)),
                byId,
                permNav,
                java.util.Set.of());

        assertThat(errors).anyMatch(e ->
                e.contains("cross-nav grant rejected"));
    }

    @Test
    void checkRoleNavigationRows_missingPermissionId_reportsError() throws Exception {
        Navigation empNav = nav("hr.employee", NavigationType.MENU, "Employee");
        ArrayNode permIds = JsonNodeFactory.instance.arrayNode();
        permIds.add("employee.view");
        List<String> errors = runRoleNavCheck(
                List.of(rn(1L, 100L, "hr.employee", permIds)),
                Map.of("hr.employee", empNav),
                Map.of(),      // permission does not exist
                java.util.Set.of());

        assertThat(errors).anyMatch(e ->
                e.contains("references missing Permission[id=employee.view]"));
    }

    @Test
    void checkRoleSensitiveFieldSetRows_missingSfsId_reportsError() throws Exception {
        List<String> errors = runSfsGrantCheck(
                List.of(rsfs(1L, 100L, "ghost-sfs")),
                java.util.Set.of("real-sfs"));

        assertThat(errors).anyMatch(e ->
                e.contains("references missing SensitiveFieldSet[id=ghost-sfs]"));
    }

    // ─── Rule ⑨ — CUSTOM scopeExpr field references must exist ───

    @Test
    void checkRoleDataScopeRows_customScopeFieldRefExists_noError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Employee", "userId")).thenReturn(true);

            ArrayNode scopes = JsonNodeFactory.instance.arrayNode();
            scopes.add(JsonNodeFactory.instance.objectNode()
                    .put("scopeType", "CUSTOM")
                    .set("scopeExpr", filterTuple("userId", "=", "42")));

            List<String> errors = runDataScopeCheck(
                    List.of(rds(1L, 100L, "Employee", scopes)));

            assertThat(errors).isEmpty();
        }
    }

    @Test
    void checkRoleDataScopeRows_customScopeFieldRefMissing_reportsError() throws Exception {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Employee", "userIdTypo")).thenReturn(false);

            ArrayNode scopes = JsonNodeFactory.instance.arrayNode();
            scopes.add(JsonNodeFactory.instance.objectNode()
                    .put("scopeType", "CUSTOM")
                    .set("scopeExpr", filterTuple("userIdTypo", "=", "42")));

            List<String> errors = runDataScopeCheck(
                    List.of(rds(1L, 100L, "Employee", scopes)));

            assertThat(errors).anyMatch(e ->
                    e.contains("missing field 'userIdTypo'") && e.contains("Employee"));
        }
    }

    // ─── Reserved role code check (bonus rule) ───

    @Test
    void checkReservedRoleCodes_nonReservedCode_reportsError() throws Exception {
        io.softa.starter.user.service.RoleService roleService =
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleService.class);
        io.softa.starter.user.entity.Role squatter = new io.softa.starter.user.entity.Role();
        squatter.setId(42L);
        squatter.setName("Squatter");
        squatter.setCode("HR_BP");   // not in the reserved set
        org.mockito.Mockito.when(roleService.searchList(
                org.mockito.ArgumentMatchers.any(io.softa.framework.orm.domain.Filters.class)))
                .thenReturn(List.of(squatter));

        PermissionRegistryValidator validator = newValidatorWith(roleService);
        List<String> errors = new ArrayList<>();
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkReservedRoleCodes", List.class);
        m.setAccessible(true);
        m.invoke(validator, errors);
        assertThat(errors).anyMatch(e -> e.contains("HR_BP"));
    }

    @Test
    void checkReservedRoleCodes_reservedCode_noError() throws Exception {
        io.softa.starter.user.service.RoleService roleService =
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleService.class);
        io.softa.starter.user.entity.Role sa = new io.softa.starter.user.entity.Role();
        sa.setId(1L);
        sa.setName("Super Admin");
        sa.setCode(io.softa.starter.user.constant.RoleConstant.CODE_SUPER_ADMIN);
        org.mockito.Mockito.when(roleService.searchList(
                org.mockito.ArgumentMatchers.any(io.softa.framework.orm.domain.Filters.class)))
                .thenReturn(List.of(sa));

        PermissionRegistryValidator validator = newValidatorWith(roleService);
        List<String> errors = new ArrayList<>();
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkReservedRoleCodes", List.class);
        m.setAccessible(true);
        m.invoke(validator, errors);
        assertThat(errors).isEmpty();
    }

    // ─── helpers ───

    private static String eqAny() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    private static Map<String, Navigation> index(List<Navigation> navs) {
        Map<String, Navigation> m = new HashMap<>();
        for (Navigation n : navs) m.put(n.getId(), n);
        return m;
    }

    /** Build a RoleNavigation row for role-nav integrity tests. */
    private static io.softa.starter.user.entity.RoleNavigation rn(
            Long id, Long roleId, String navId,
            tools.jackson.databind.JsonNode permIds) {
        io.softa.starter.user.entity.RoleNavigation r = new io.softa.starter.user.entity.RoleNavigation();
        r.setId(id);
        r.setRoleId(roleId);
        r.setNavigationId(navId);
        if (permIds != null) r.setPermissionIds(permIds);
        return r;
    }

    /** Build a filter tuple like {@code ["field","=","value"]}. */
    private static ArrayNode filterTuple(String field, String op, String value) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        arr.add(field).add(op).add(value);
        return arr;
    }

    /** Instantiate the validator with a specific RoleService (other deps mocked). */
    private static PermissionRegistryValidator newValidatorWith(
            io.softa.starter.user.service.RoleService roleService) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        io.softa.framework.orm.service.ModelService modelService =
                org.mockito.Mockito.mock(io.softa.framework.orm.service.ModelService.class);
        io.softa.starter.user.service.RoleNavigationService rns =
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleNavigationService.class);
        io.softa.starter.user.service.NavigationModelResolver navRes =
                org.mockito.Mockito.mock(io.softa.starter.user.service.NavigationModelResolver.class);
        io.softa.starter.user.service.RoleDataScopeService rds =
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleDataScopeService.class);
        io.softa.starter.user.service.RoleSensitiveFieldSetService rsfsSvc =
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleSensitiveFieldSetService.class);
        // Rule ① (endpoint coverage) moved to permission-starter — the validator
        // no longer takes EndpointIndex / InterceptorProperties / handlerMappings.
        return new PermissionRegistryValidator(
                modelService, roleService, rns, rds, rsfsSvc, navRes);
    }

    /** Invoke the private non-static {@code checkRoleNavigationRows}. The
     *  {@code sfsIds} param is retained for call-site compatibility but is no
     *  longer forwarded — SFS existence moved to {@link #runSfsGrantCheck}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> runRoleNavCheck(
            List<io.softa.starter.user.entity.RoleNavigation> grants,
            Map<String, Navigation> navById,
            Map<String, String> permNavById,
            java.util.Set<String> sfsIds) throws Exception {
        PermissionRegistryValidator validator = newValidatorWith(
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleService.class));
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkRoleNavigationRows",
                List.class, Map.class, Map.class, List.class);
        m.setAccessible(true);
        List<String> errors = new ArrayList<>();
        m.invoke(validator, grants, navById, permNavById, errors);
        return errors;
    }

    /** Build a role_data_scope row for scope-integrity tests. */
    private static io.softa.starter.user.entity.RoleDataScope rds(
            Long id, Long roleId, String model, tools.jackson.databind.JsonNode dataScopes) {
        io.softa.starter.user.entity.RoleDataScope r = new io.softa.starter.user.entity.RoleDataScope();
        r.setId(id);
        r.setRoleId(roleId);
        r.setModel(model);
        r.setDataScopes(dataScopes);
        return r;
    }

    /** Build a role_sensitive_field_set row for SFS-grant tests. */
    private static io.softa.starter.user.entity.RoleSensitiveFieldSet rsfs(
            Long id, Long roleId, String sid) {
        io.softa.starter.user.entity.RoleSensitiveFieldSet r =
                new io.softa.starter.user.entity.RoleSensitiveFieldSet();
        r.setId(id);
        r.setRoleId(roleId);
        r.setSensitiveFieldSetId(sid);
        return r;
    }

    /** Invoke the private non-static {@code checkRoleDataScopeRows}. */
    private static List<String> runDataScopeCheck(
            List<io.softa.starter.user.entity.RoleDataScope> grants) throws Exception {
        PermissionRegistryValidator validator = newValidatorWith(
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleService.class));
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkRoleDataScopeRows", List.class, List.class);
        m.setAccessible(true);
        List<String> errors = new ArrayList<>();
        m.invoke(validator, grants, errors);
        return errors;
    }

    /** Invoke the private non-static {@code checkRoleSensitiveFieldSetRows}. */
    private static List<String> runSfsGrantCheck(
            List<io.softa.starter.user.entity.RoleSensitiveFieldSet> grants,
            java.util.Set<String> registryIds) throws Exception {
        PermissionRegistryValidator validator = newValidatorWith(
                org.mockito.Mockito.mock(io.softa.starter.user.service.RoleService.class));
        Method m = PermissionRegistryValidator.class.getDeclaredMethod(
                "checkRoleSensitiveFieldSetRows", List.class, java.util.Set.class, List.class);
        m.setAccessible(true);
        List<String> errors = new ArrayList<>();
        m.invoke(validator, grants, registryIds, errors);
        return errors;
    }
}
