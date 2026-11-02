package io.softa.starter.flow.service;

import java.util.List;

import io.softa.starter.flow.runtime.spi.DirectoryDepartment;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;

/**
 * The directory behind the people and department pickers, from the {@code OrganizationService}, the tenant always
 * applying. A person's pickers — who they hand work to, copy or delegate to, whose requests they filter by — search it
 * with their own permissions, so their roles' scope on the directory model decides whom they find, and see only people
 * who can act. The flow designer and the operations console read all of it, past the caller's permissions: people who
 * can no longer act, and accounts without an employee record, each saying whether it can act. Departments and the
 * names of people already picked are read past the caller's permissions.
 */
public interface FlowDirectoryService {

    /**
     * People whose login account signs in, matched on {@code keyword} (all when blank), by name, as the caller may read
     * the directory. Whether each can act is not said: they all can. {@code limit} defaults to 20 and may not exceed
     * 50: a picker narrows by typing.
     */
    List<DirectoryPerson> searchPeople(String keyword, Integer limit);

    /**
     * These login accounts by name, in the order asked, whatever the caller's permissions — the people a picker already
     * holds — an account without an employee record by its name alone. Whether each can act is not said. Ids naming
     * no account are left out; at most 200.
     */
    List<DirectoryPerson> getPeople(List<Long> userIds);

    /**
     * For the flow designer and the operations console: every login account matching {@code keyword} (all when blank),
     * employees and accounts without an employee record, by name, each saying whether it can act; only those that can
     * unless {@code includeInactive} — as when moving work off someone who left. {@code limit} as for
     * {@link #searchPeople}.
     */
    List<DirectoryPerson> searchAllPeople(String keyword, boolean includeInactive, Integer limit);

    /** {@link #getPeople}, each saying whether it can act: for the flow designer and the operations console. */
    List<DirectoryPerson> getAllPeople(List<Long> userIds);

    /** Open departments matching {@code keyword} (all when blank), by name; {@code limit} as for people. */
    List<DirectoryDepartment> searchDepartments(String keyword, Integer limit);

    /** These departments, closed ones included, in the order asked; at most 200. */
    List<DirectoryDepartment> getDepartments(List<Long> deptIds);
}
