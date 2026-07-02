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

    // ─── helpers ───

    private static Map<String, Navigation> index(List<Navigation> navs) {
        Map<String, Navigation> m = new HashMap<>();
        for (Navigation n : navs) m.put(n.getId(), n);
        return m;
    }
}
