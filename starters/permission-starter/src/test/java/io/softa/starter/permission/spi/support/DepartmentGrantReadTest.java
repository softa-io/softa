package io.softa.starter.permission.spi.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.scope.IdPath;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading a role's department grant into the snapshot: the three states, and the expansion of each
 * granted department to its subtree by {@code idPath}.
 */
class DepartmentGrantReadTest {

    /** The tree: 1 ⊃ 5 ⊃ 9, 1 ⊃ 12, and 50 on its own. 15 sits beside 1, sharing its leading digit. */
    private static final Map<Long, String> TREE = new LinkedHashMap<>();

    static {
        TREE.put(1L, "/1");
        TREE.put(5L, "/1/5");
        TREE.put(9L, "/1/5/9");
        TREE.put(12L, "/1/12");
        TREE.put(15L, "/15");
        TREE.put(50L, "/50");
    }

    private static ModelService<Long> lastModelService;

    private static <T> T view(Class<T> type, Map<String, Object> values) {
        return JsonUtils.stringToObject(JsonUtils.objectToString(values), type);
    }

    private static JsonNode json(String text) {
        return JsonUtils.stringToObject(text, JsonNode.class);
    }

    private static String subtree(String deptId) {
        return "{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"" + deptId + "\"}}";
    }

    /**
     * Two roles with the given department grants (null = no grant), built with or without the column
     * declared on the role model.
     */
    @SuppressWarnings("unchecked")
    private static PermissionInfo build(String role1Grant, String role2Grant, boolean columnDeclared) {
        ModelService<Long> modelService = mock(ModelService.class);
        lastModelService = modelService;
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 1)),
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 2))));
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class))).thenAnswer(inv -> {
            FlexQuery query = inv.getArgument(1);
            boolean asked = query.getFields().contains(DefaultPermissionSnapshotProvider.F_DEPARTMENT_GRANT);
            List<DefaultPermissionSnapshotProvider.RoleView> roles = new ArrayList<>();
            roles.add(role(1, asked ? role1Grant : null));
            roles.add(role(2, asked ? role2Grant : null));
            return roles;
        });
        for (String model : List.of("RoleNavigation", "RoleDataScope", "RoleSensitiveFieldSet", "Navigation")) {
            when(modelService.searchList(eq(model), any(FlexQuery.class), any(Class.class))).thenReturn(List.of());
        }
        List<String> roots = new ArrayList<>();
        when(modelService.searchList(eq(ModelConstant.DEPARTMENT_MODEL), any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery query = inv.getArgument(1);
            List<Map<String, Object>> rows = new ArrayList<>();
            String filter = query.getFilters().toString();
            if (query.getFields().contains(IdPath.FIELD)) {
                // The roots, named by id.
                roots.clear();
                TREE.forEach((id, path) -> {
                    if (filter.matches(".*[\\[,]" + id + "[\\],].*")) {
                        rows.add(Map.of(ModelConstant.ID, id, IdPath.FIELD, path));
                        roots.add(path);
                    }
                });
            } else {
                // The subtrees of those roots, on whole segments.
                TREE.forEach((id, path) -> {
                    if (roots.stream().anyMatch(r -> path.equals(r) || path.startsWith(r + IdPath.SEPARATOR))) {
                        rows.add(Map.of(ModelConstant.ID, id));
                    }
                });
            }
            return rows;
        });
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.existModel(ModelConstant.DEPARTMENT_MODEL)).thenReturn(true);
            models.when(() -> ModelManager.getModelFieldOrNull(ModelConstant.DEPARTMENT_MODEL, IdPath.FIELD))
                    .thenReturn(mock(MetaField.class));
            if (columnDeclared) {
                models.when(() -> ModelManager.getModelFieldOrNull("Role",
                        DefaultPermissionSnapshotProvider.F_DEPARTMENT_GRANT)).thenReturn(mock(MetaField.class));
            }
            DefaultPermissionSnapshotProvider provider = new DefaultPermissionSnapshotProvider(null, modelService,
                    mock(SensitiveFieldSetCache.class), () -> null, List.of(), List.of());
            provider.build();
            return provider.doLoadFromDb(7L, 42L);
        }
    }

    private static DefaultPermissionSnapshotProvider.RoleView role(int id, String grant) {
        Map<String, Object> values = new HashMap<>(Map.of("id", id, "active", true));
        DefaultPermissionSnapshotProvider.RoleView view = view(DefaultPermissionSnapshotProvider.RoleView.class, values);
        view.setDepartmentGrant(grant == null ? null : json(grant));
        return view;
    }

    @Test
    void aRoleWithoutAGrantIsUnrestrictedAndCostsNoRead() {
        PermissionInfo info = build(null, null, true);

        assertThat(info.getRoleGrants()).allSatisfy(g -> assertThat(g.getGrantedDepartmentIds()).isNull());
        verify(lastModelService, never()).searchList(eq(ModelConstant.DEPARTMENT_MODEL), any(FlexQuery.class));
    }

    @Test
    void aGrantedDepartmentReachesItsWholeSubtreeAndNothingBesideIt() {
        PermissionInfo info = build("[" + subtree("1") + "]", "[" + subtree("5") + "," + subtree("50") + "]", true);

        // 15 shares the leading digit of 1 but is not under it.
        assertThat(info.getRoleGrants().get(0).getGrantedDepartmentIds()).containsExactlyInAnyOrder(1L, 5L, 9L, 12L);
        assertThat(info.getRoleGrants().get(1).getGrantedDepartmentIds()).containsExactlyInAnyOrder(5L, 9L, 50L);
    }

    @Test
    void allIsUnrestrictedAndEmptyIsNothing() {
        PermissionInfo info = build("[{\"scopeType\":\"ALL\"}]", "[]", true);

        assertThat(info.getRoleGrants().get(0).getGrantedDepartmentIds()).isNull();
        assertThat(info.getRoleGrants().get(1).getGrantedDepartmentIds()).isEmpty();
        verify(lastModelService, never()).searchList(eq(ModelConstant.DEPARTMENT_MODEL), any(FlexQuery.class));
    }

    @Test
    void aGrantNothingCanResolveFailsClosed() {
        // A root that no longer exists, and a rule of a type the grant does not take.
        PermissionInfo info = build("[" + subtree("777") + "]", "[{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]", true);

        assertThat(info.getRoleGrants().get(0).getGrantedDepartmentIds()).isEmpty();
        assertThat(info.getRoleGrants().get(1).getGrantedDepartmentIds()).isEmpty();
    }

    @Test
    void rolesWithTheSameGrantShareOneResolution() {
        String grant = "[" + subtree("5") + "]";
        PermissionInfo info = build(grant, grant, true);

        assertThat(info.getRoleGrants().get(1).getGrantedDepartmentIds()).containsExactlyInAnyOrder(5L, 9L);
        // One read of the roots, one of their subtrees — for both roles.
        verify(lastModelService, times(2)).searchList(eq(ModelConstant.DEPARTMENT_MODEL), any(FlexQuery.class));
    }

    @Test
    void anApplicationWithoutTheColumnBuildsTheSnapshotItAlwaysDid() {
        PermissionInfo info = build("[" + subtree("1") + "]", "[]", false);

        assertThat(info.getRoleGrants()).allSatisfy(g -> assertThat(g.getGrantedDepartmentIds()).isNull());
    }
}
