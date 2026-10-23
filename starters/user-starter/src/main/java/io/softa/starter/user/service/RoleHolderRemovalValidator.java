package io.softa.starter.user.service;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;

/**
 * The last holder of a role that must always have one — the tenant administrator, the platform
 * administrator — cannot be taken off it, whichever write does it.
 *
 * <p>The typed membership service refused a delete that left such a role without holders, and only that
 * service did: a link removed through {@code UserAccount.roles}, or a membership moved to another role in
 * place, went around it and could leave a tenant with no administrator. A validator runs on every write
 * root, so every path now meets the same check.
 */
@Component
@Order(25)
public class RoleHolderRemovalValidator implements ModelWriteValidator {

    private static final String MEMBERSHIP = "UserRoleRel";
    private static final String ROLE_ID = "roleId";

    private final ModelService<?> modelService;

    public RoleHolderRemovalValidator(ModelService<?> modelService) {
        this.modelService = modelService;
    }

    @Override
    public boolean supports(String modelName) {
        return MEMBERSHIP.equals(modelName);
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) return;
        Map<Object, Long> removedByRole = new HashMap<>();
        inSystemContext(() -> modelService.searchList(MEMBERSHIP,
                new FlexQuery(List.of("id", ROLE_ID), new Filters().in("id", ids))))
                .forEach(rel -> {
                    Object role = idOf(rel.get(ROLE_ID));
                    if (role != null) removedByRole.merge(role, 1L, Long::sum);
                });
        removedByRole.forEach(this::assertKeepsAHolder);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (!ctx.patch().containsKey(ROLE_ID) || ctx.originalRow() == null) return;
        Object before = idOf(ctx.originalRow().get(ROLE_ID));
        if (before != null && !Objects.equals(String.valueOf(before), String.valueOf(idOf(ctx.patch().get(ROLE_ID))))) {
            assertKeepsAHolder(before, 1L);
        }
    }

    private void assertKeepsAHolder(Object roleId, long removed) {
        List<Map<String, Object>> roles = inSystemContext(() -> modelService.searchList("Role",
                new FlexQuery(List.of("id", "name", "code"), new Filters().eq("id", roleId))));
        if (roles.isEmpty()) return;
        Role role = new Role();
        role.setName(String.valueOf(roles.getFirst().get("name")));
        Object code = roles.getFirst().get("code");
        role.setCode(code == null ? null : code.toString());
        if (!RoleConstant.requiresAtLeastOneHolder(role)) return;
        long holders = inSystemContext(() -> modelService.count(MEMBERSHIP, new Filters().eq(ROLE_ID, roleId)));
        if (holders - removed <= 0) {
            throw new BusinessException("Cannot revoke the last holder of system role ''{0}'' (code={1})",
                    role.getName(), role.getCode());
        }
    }

    private static Object idOf(Object value) {
        return value instanceof Map<?, ?> reference ? reference.get("id") : value;
    }
}
