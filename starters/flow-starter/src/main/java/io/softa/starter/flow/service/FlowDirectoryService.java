package io.softa.starter.flow.service;

import java.util.List;

import io.softa.starter.flow.runtime.spi.DirectoryDepartment;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;

/**
 * The directory every signed-in person's pickers read: who they can hand work to, copy or delegate to, and the
 * departments a flow can name — whatever they may read of the HR models. Read from the {@code OrganizationService}
 * past the caller's row scope, the tenant still applying.
 */
public interface FlowDirectoryService {

    /**
     * Employees with a login account matching {@code keyword} (all when blank), by name; only those who can act unless
     * {@code includeInactive}. {@code limit} defaults to 20 and may not exceed 50: a picker narrows by typing.
     */
    List<DirectoryPerson> searchPeople(String keyword, boolean includeInactive, Integer limit);

    /**
     * These login accounts, in the order asked, whether they can act or not: an employee as {@link #searchPeople}
     * shows them, an account without an employee record — a tenant administrator — by its name alone. Ids naming no
     * account are left out; at most 200.
     */
    List<DirectoryPerson> getPeople(List<Long> userIds);

    /** Open departments matching {@code keyword} (all when blank), by name; {@code limit} as for people. */
    List<DirectoryDepartment> searchDepartments(String keyword, Integer limit);

    /** These departments, closed ones included, in the order asked; at most 200. */
    List<DirectoryDepartment> getDepartments(List<Long> deptIds);
}
