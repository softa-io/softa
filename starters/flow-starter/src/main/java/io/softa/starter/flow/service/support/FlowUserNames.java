package io.softa.starter.flow.service.support;

import java.util.*;
import java.util.function.BiFunction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;

/**
 * Names the users a record's approval views mention — its history, its preview: an employee's name
 * from the organization service, else the account's nickname, else its username. The names are read
 * past the caller's permissions, so a view names its users only once the caller is allowed to see it.
 */
@Component
public class FlowUserNames {

    /** The account model whose nickname or username names a user without an employee profile. */
    private static final String USER_ACCOUNT_MODEL = "UserAccount";

    private final ObjectProvider<OrganizationService> organizationService;

    private final ModelService<Long> modelService;

    @SuppressWarnings("unchecked")
    public FlowUserNames(ObjectProvider<OrganizationService> organizationService, ModelService<?> modelService) {
        this.organizationService = organizationService;
        this.modelService = (ModelService<Long>) modelService;
    }

    /** Names each actor in place: null actors are skipped, and a user who has no name keeps a null one. */
    public void name(Collection<FlowRecordApprovalsView.Actor> actors) {
        List<FlowRecordApprovalsView.Actor> users = actors.stream().filter(Objects::nonNull).toList();
        Map<String, String> names = ContextUtils.withoutPermissionChecks(
                () -> userNames(users.stream().map(FlowRecordApprovalsView.Actor::getId).toList()));
        users.forEach(user -> user.setName(names.get(user.getId())));
    }

    /**
     * Names each employee in place — a record's subject, whose id is an employee id, not a user id — from
     * the organization service. Null entries are skipped; an employee who has no name keeps a null one.
     */
    public void nameEmployees(Collection<FlowRecordApprovalsView.Actor> employees) {
        List<FlowRecordApprovalsView.Actor> named = employees.stream().filter(Objects::nonNull).toList();
        Map<Long, String> names = organizationNames(named.stream().map(e -> toLong(e.getId())).toList(),
                OrganizationService::getEmployeeNames);
        named.forEach(employee -> employee.setName(names.get(toLong(employee.getId()))));
    }

    /** Names each department in place from the organization service; null entries are skipped. */
    public void nameDepartments(Collection<FlowInstanceSummaryView.Department> departments) {
        List<FlowInstanceSummaryView.Department> named = departments.stream().filter(Objects::nonNull).toList();
        Map<Long, String> names = organizationNames(
                named.stream().map(FlowInstanceSummaryView.Department::getId).toList(), OrganizationService::getDeptNames);
        named.forEach(department -> department.setName(names.get(department.getId())));
    }

    /** These users' names by user id, as {@link #name} gives them; a user without a name is left out. */
    public Map<String, String> namesOf(Collection<String> userIds) {
        return ContextUtils.withoutPermissionChecks(() -> userNames(userIds));
    }

    private Map<Long, String> organizationNames(List<Long> ids,
                                                BiFunction<OrganizationService, Collection<Long>, Map<Long, String>> lookup) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        OrganizationService organization = organizationService == null ? null : organizationService.getIfAvailable();
        if (distinct.isEmpty() || organization == null) {
            return Map.of();
        }
        return ContextUtils.withoutPermissionChecks(() -> lookup.apply(organization, distinct));
    }

    private Map<String, String> userNames(Collection<String> userIds) {
        List<Long> ids = userIds.stream().map(FlowUserNames::toLong).filter(Objects::nonNull).distinct().toList();
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        OrganizationService organization = organizationService == null ? null : organizationService.getIfAvailable();
        if (organization != null) {
            for (ApproverInfo user : organization.getUsersByIds(ids)) {
                if (user.getUserId() != null && StringUtils.hasText(user.getUserName())) {
                    names.put(user.getUserIdAsString(), user.getUserName());
                }
            }
        }
        List<Long> unnamed = ids.stream().filter(id -> !names.containsKey(id.toString())).toList();
        if (!unnamed.isEmpty() && ModelManager.existModel(USER_ACCOUNT_MODEL)) {
            for (Map<String, Object> account : modelService.getByIds(USER_ACCOUNT_MODEL, unnamed,
                    List.of(ModelConstant.ID, "nickname", "username"))) {
                Object nickname = account.get("nickname");
                Object name = nickname != null && StringUtils.hasText(nickname.toString()) ? nickname : account.get("username");
                if (name != null) {
                    names.put(String.valueOf(account.get(ModelConstant.ID)), name.toString());
                }
            }
        }
        return names;
    }

    private static Long toLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
