package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.dto.RoleGrantView;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The effective-permissions panel lists each role on its own: its actions, rows and sensitive sets,
 * with a condition beside the rules it narrows.
 */
class RoleGrantsForTest {

    private static JsonNode json(String text) {
        return JsonUtils.stringToObject(text, JsonNode.class);
    }

    private static Role role(long id, String name) {
        Role r = new Role();
        r.setId(id);
        r.setName(name);
        r.setActive(true);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachRoleIsListedWithItsOwnGrant() {
        UserRoleRelService rels = mock(UserRoleRelService.class);
        RoleService roles = mock(RoleService.class);
        RoleNavigationService navs = mock(RoleNavigationService.class);
        RoleSensitiveFieldSetService sfs = mock(RoleSensitiveFieldSetService.class);
        RoleDataScopeService scopes = mock(RoleDataScopeService.class);
        ModelService<Long> modelService = mock(ModelService.class);

        UserRoleRel rel1 = new UserRoleRel();
        rel1.setRoleId(1L);
        UserRoleRel rel2 = new UserRoleRel();
        rel2.setRoleId(2L);
        when(rels.searchList(any(FlexQuery.class))).thenReturn(List.of(rel1, rel2));
        when(roles.searchList(any(FlexQuery.class))).thenReturn(List.of(role(1, "Directory"), role(2, "Department HR")));

        RoleNavigation nav1 = new RoleNavigation();
        nav1.setRoleId(1L);
        nav1.setPermissionIds(json("[\"employee.view\"]"));
        RoleNavigation nav2 = new RoleNavigation();
        nav2.setRoleId(2L);
        nav2.setPermissionIds(json("[\"employee.view\",\"employee.update\"]"));
        when(navs.searchList(any(FlexQuery.class))).thenReturn(List.of(nav1, nav2));

        RoleDataScope all = new RoleDataScope();
        all.setRoleId(1L);
        all.setModel("Employee");
        all.setDataScopes(json("[{\"scopeType\":\"ALL\"}]"));
        RoleDataScope dept = new RoleDataScope();
        dept.setRoleId(2L);
        dept.setModel("Employee");
        dept.setDataScopes(json("[{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]"));
        dept.setScopeCondition(json("[[\"type\",\"=\",\"Contractor\"]]"));
        when(scopes.searchList(any(FlexQuery.class))).thenReturn(List.of(all, dept));

        RoleSensitiveFieldSet salary = new RoleSensitiveFieldSet();
        salary.setRoleId(2L);
        salary.setSensitiveFieldSetId("salary");
        when(sfs.searchList(any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery q = inv.getArgument(0);
            return q.getFilters().toString().contains("2") ? List.of(salary) : List.of();
        });
        when(modelService.searchList(eq("SensitiveFieldSet"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", "salary", "model", "Employee")));

        UiContextBuilder builder = new UiContextBuilder(rels, roles, navs, sfs, scopes, null, modelService);
        List<RoleGrantView> grants = builder.roleGrantsFor(42L);

        assertThat(grants).extracting(RoleGrantView::getRoleName).containsExactly("Directory", "Department HR");
        assertThat(grants.get(0).getPermissions()).containsExactly("employee.view");
        assertThat(grants.get(0).getModelScopeConditions()).isEmpty();
        assertThat(grants.get(1).getPermissions()).containsExactlyInAnyOrder("employee.view", "employee.update");
        assertThat(grants.get(1).getModelScopeMap().get("Employee")).hasSize(1);
        assertThat(grants.get(1).getModelScopeConditions().get("Employee"))
                .isEqualTo(json("[[\"type\",\"=\",\"Contractor\"]]"));
        assertThat(grants.get(1).getModelSensitiveFieldSetsMap().get("Employee")).containsExactly("salary");
    }
}
