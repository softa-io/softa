package io.softa.starter.user.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.enums.LogicOperator;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.dto.BulkUpdateParams;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.UserRosterScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An account's status moves only through its operations, an account is never deleted, and the search
 * box finds an account by its employee as well as by its own fields.
 */
class UserAccountStatusDeleteSearchTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final UserAccountService service = mock(UserAccountService.class);
    private final UserAccountController controller = new UserAccountController();

    @BeforeEach
    void wire() {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.updateCarryingLoginIdentifiers(any(), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        ReflectionTestUtils.setField(controller, "permissionCacheInvalidator", mock(PermissionCacheInvalidator.class));
        ReflectionTestUtils.setField(controller, "rosterScope",
                new UserRosterScope(mock(RoleService.class), mock(UserRoleRelService.class)));
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private static <T> T asTenantAdmin(Supplier<T> body) {
        Context ctx = new Context();
        ctx.setTenantId(2L);
        ctx.setUserId(1L);
        return ContextHolder.callWith(ctx, body::get);
    }

    // ─── status ───

    @Test
    void aBulkEditCannotSetTheStatus() {
        // The reported case: a Pending account bulk-edited to Active, with nobody behind it.
        BulkUpdateParams params = new BulkUpdateParams();
        params.setFilters(Filters.of("id", Operator.IN, List.of(5L)));
        params.setValues(new HashMap<>(Map.of("status", "Active")));

        asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.updateByFilter(params))
                    .hasMessageContaining("only through its actions");
            return null;
        });
        verify(modelService, never()).updateByFilter(eq("UserAccount"), any(), anyMap());
    }

    @Test
    void anEditOfOneAccountCannotSetTheStatus() {
        Map<String, Object> row = new HashMap<>(Map.of("id", 5L, "status", "Active"));
        try (MockedStatic<IdUtils> ids = Mockito.mockStatic(IdUtils.class)) {
            asTenantAdmin(() -> {
                assertThatThrownBy(() -> controller.updateOne(row))
                        .hasMessageContaining("only through its actions");
                assertThatThrownBy(() -> controller.updateList(List.of(new HashMap<>(row))))
                        .hasMessageContaining("only through its actions");
                return null;
            });
        }
        verify(modelService, never()).updateOne(eq("UserAccount"), anyMap());
        verify(modelService, never()).updateList(eq("UserAccount"), any());
    }

    // ─── delete ───

    @Test
    void noAccountIsDeleted() {
        assertThatThrownBy(() -> controller.deleteById(5L)).hasMessageContaining("not deleted");
        assertThatThrownBy(() -> controller.deleteByIds(List.of(5L, 6L))).hasMessageContaining("not deleted");
        verify(modelService, never()).deleteById(eq("UserAccount"), any());
        verify(modelService, never()).deleteByIds(eq("UserAccount"), any());
    }

    // ─── search ───

    private Filters widened(Filters filters, List<Map<String, Object>> employees) {
        when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenReturn(employees);
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            asTenantAdmin(() -> {
                ReflectionTestUtils.invokeMethod(controller, "searchEmployeesToo", filters);
                return null;
            });
        }
        return filters;
    }

    @Test
    void aSearchAlsoFindsTheAccountOfAnEmployeeByNameOrCode() {
        // An account nobody has joined has no nickname or username; the employee's name is how HR
        // knows the person.
        Filters search = Filters.of("searchName", Operator.CONTAINS, "Ada");

        Filters result = widened(search, List.of(Map.of("id", 70L, "userId", 7L), Map.of("id", 71L, "userId", 8L)));

        assertThat(result.getType()).isEqualTo(FilterType.TREE);
        assertThat(result.getLogicOperator()).isEqualTo(LogicOperator.OR);
        assertThat(result.getChildren()).hasSize(2);
        FilterUnit own = result.getChildren().get(0).getFilterUnit();
        assertThat(own.getField()).isEqualTo("searchName");
        assertThat(own.getValue()).isEqualTo("Ada");
        FilterUnit byEmployee = result.getChildren().get(1).getFilterUnit();
        assertThat(byEmployee.getField()).isEqualTo("id");
        assertThat(byEmployee.getOperator()).isEqualTo(Operator.IN);
        assertThat(byEmployee.getValue()).isEqualTo(List.of(7L, 8L));
    }

    @Test
    void aSearchInsideOtherConditionsIsWidenedWhereItStands() {
        Filters filters = Filters.and(Filters.of("status", Operator.EQUAL, "Pending"),
                Filters.of("searchName", Operator.CONTAINS, "E1000009"));

        widened(filters, List.of(Map.of("id", 70L, "userId", Map.of("id", 9L))));

        assertThat(filters.getLogicOperator()).isNotEqualTo(LogicOperator.OR);
        assertThat(filters.getChildren().get(0).getFilterUnit().getField()).isEqualTo("status");
        Filters search = filters.getChildren().get(1);
        assertThat(search.getLogicOperator()).isEqualTo(LogicOperator.OR);
        assertThat(search.getChildren().get(1).getFilterUnit().getValue()).isEqualTo(List.of(9L));
    }

    @Test
    void noMatchingEmployeeLeavesTheSearchAsItWas() {
        Filters search = Filters.of("searchName", Operator.CONTAINS, "nobody");

        widened(search, List.of());

        assertThat(search.getType()).isEqualTo(FilterType.LEAF);
        assertThat(search.getFilterUnit().getField()).isEqualTo("searchName");
    }
}
