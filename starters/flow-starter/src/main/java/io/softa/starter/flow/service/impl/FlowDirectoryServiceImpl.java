package io.softa.starter.flow.service.impl;

import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.starter.flow.runtime.spi.DirectoryDepartment;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.FlowDirectoryService;
import io.softa.starter.flow.service.support.FlowUserNames;

@Service
public class FlowDirectoryServiceImpl implements FlowDirectoryService {

    /** Rows a search returns when the caller names no limit. */
    static final int DEFAULT_LIMIT = 20;

    /** The most rows a search returns. */
    static final int MAX_LIMIT = 50;

    /** The most ids one lookup names: the values picked in one picker. */
    static final int MAX_IDS = 200;

    private final ObjectProvider<OrganizationService> organizationService;

    private final FlowUserNames userNames;

    public FlowDirectoryServiceImpl(ObjectProvider<OrganizationService> organizationService, FlowUserNames userNames) {
        this.organizationService = organizationService;
        this.userNames = userNames;
    }

    @Override
    public List<DirectoryPerson> searchPeople(String keyword, boolean includeInactive, Integer limit) {
        int size = limitOf(limit);
        return ask(organization -> organization.searchPeople(keyword, includeInactive, size), List.of());
    }

    @Override
    public List<DirectoryPerson> getPeople(List<Long> userIds) {
        List<Long> ids = idsOf(userIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, DirectoryPerson> people = new HashMap<>();
        ask(organization -> organization.getPeople(ids), List.<DirectoryPerson>of())
                .forEach(person -> people.putIfAbsent(person.id(), person));
        List<Long> accounts = ids.stream().filter(id -> !people.containsKey(id)).toList();
        if (!accounts.isEmpty()) {
            // Named as the approval views name them; the organization knows which of them can act.
            Map<String, String> names = userNames.namesOf(accounts.stream().map(String::valueOf).toList());
            Map<Long, String> unusable = ask(organization -> organization.getUnusableAccounts(accounts), Map.of());
            for (Long id : accounts) {
                String name = names.get(String.valueOf(id));
                if (name != null) {
                    people.put(id, new DirectoryPerson(id, name, null, null, null, !unusable.containsKey(id)));
                }
            }
        }
        return ids.stream().map(people::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<DirectoryDepartment> searchDepartments(String keyword, Integer limit) {
        int size = limitOf(limit);
        return ask(organization -> organization.searchDepartments(keyword, size), List.of());
    }

    @Override
    public List<DirectoryDepartment> getDepartments(List<Long> deptIds) {
        List<Long> ids = idsOf(deptIds);
        return ids.isEmpty() ? List.of() : ask(organization -> organization.getDepartments(ids), List.of());
    }

    /** What the organization answers, read past the caller's permissions; {@code none} without an organization. */
    private <T> T ask(Function<OrganizationService, T> question, T none) {
        OrganizationService organization = organizationService.getIfAvailable();
        return organization == null ? none : ContextUtils.withoutPermissionChecks(() -> question.apply(organization));
    }

    private static int limitOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        Assert.isTrue(limit > 0 && limit <= MAX_LIMIT, "The directory returns 1 to {0} rows, not {1}.", MAX_LIMIT, limit);
        return limit;
    }

    private static List<Long> idsOf(List<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        Assert.isTrue(distinct.size() <= MAX_IDS, "The directory names at most {0} ids at a time.", MAX_IDS);
        return distinct;
    }
}
