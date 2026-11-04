package io.softa.starter.flow.runtime.spi.impl;

import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.starter.flow.runtime.spi.DirectoryDepartment;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The directory {@link FlowOrgService} serves the pickers: people with a login account, matched on the directory
 * model's search fields, by name; department and position named by the read itself. A person's own search reads the
 * directory model with their permissions and finds only accounts that sign in; the designer's and the operations
 * console's reach every account, past the caller's permissions, as every other read does.
 */
class FlowOrgServiceDirectoryTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final OrganizationServiceProperties props = new OrganizationServiceProperties();
    private final FlowOrgService org = new FlowOrgService(modelService, props);
    private final Map<String, List<Map<String, Object>>> answers = new HashMap<>();
    private final List<FlexQuery> queries = new ArrayList<>();
    private final List<String> models = new ArrayList<>();
    private final List<Boolean> permissionChecksSkipped = new ArrayList<>();

    @BeforeEach
    void answerReads() {
        when(modelService.searchList(anyString(), any(FlexQuery.class))).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            models.add(model);
            queries.add(invocation.getArgument(1));
            permissionChecksSkipped.add(ContextHolder.existContext() && ContextHolder.getContext().isSkipPermissionCheck());
            return answers.getOrDefault(model, List.of());
        });
    }

    @Test
    void aSearchFindsEmployeesWhoseAccountSignsInByCodeOrNameInOneRead() {
        answers.put("Employee", List.of(employee(1001L, "Alice Tan", "E001", "Finance", "Accountant")));

        List<DirectoryPerson> people = withModels(true, true, () -> org.searchPeople(" ali ", 20));

        assertEquals(List.of(new DirectoryPerson(1001L, "Alice Tan", "E001", "Finance", "Accountant", true)), people);
        assertEquals(List.of("Employee"), models, "whether the account signs in is a condition of the read");
        assertEquals(List.of(false), permissionChecksSkipped, "read with the caller's own permissions");
        FlexQuery query = queries.getFirst();
        String filters = query.getFilters().toString();
        assertTrue(filters.contains("[\"userId\",\"IS SET\","), filters);
        assertTrue(filters.contains("[\"searchName\",\"CONTAINS\",\"ali\"]"), filters);
        assertTrue(filters.contains("[\"userId.status\",\"IN\","), filters);
        assertEquals(ConvertType.REFERENCE, query.getConvertType());
        assertEquals(20, query.getLimitSize());
        // By name, then id: asked again for more, two people of one name keep their places.
        assertEquals(Orders.ofAsc("fullName").addAsc("id").toString(), query.getOrders().toString());
        assertTrue(query.getFields().containsAll(List.of("userId", "fullName", "code", "departmentId", "jobPositionId")));
    }

    @Test
    void theDirectoryModelIsWhatThePickersReadAndApproverRulesDoNot() {
        props.setDirectoryModel("EmployeeDirectory");
        answers.put("EmployeeDirectory", List.of(employee(1001L, "Alice Tan", "E001", "Finance", null)));

        withModels(true, true, () -> {
            org.searchPeople("ali", 20);
            org.getPeople(List.of(1001L));
            return org.getUsersByDeptIds(List.of(10L), false);
        });

        // The search and the names of picked people read the directory; finding approvers by rule reads employees.
        assertEquals("EmployeeDirectory", models.get(0));
        assertEquals("EmployeeDirectory", models.get(1));
        assertTrue(models.contains("Employee"), models.toString());
        assertEquals(false, permissionChecksSkipped.get(0), "the search, as the caller");
        assertEquals(true, permissionChecksSkipped.get(1), "the names, past their permissions");
    }

    @Test
    void theWholeDirectoryHasEveryAccountSayingWhichCannotAct() {
        answers.put("Employee", List.of(employee(1001L, "Alice Tan", "E001", null, null),
                employee(1002L, "Bob Lim", "E002", null, null)));
        answers.put("UserAccount", List.of(Map.of("id", 1001L, "status", "Active"),
                Map.of("id", 1002L, "status", "Deactivated"), Map.of("id", 1003L, "status", "Active")));
        // Accounts by their own name: Alice's, who is an employee, and a tenant administrator's, who is not.
        when(modelService.searchName(anyString(), any(FlexQuery.class), any())).thenAnswer(invocation -> {
            queries.add(invocation.getArgument(1));
            permissionChecksSkipped.add(ContextHolder.existContext() && ContextHolder.getContext().isSkipPermissionCheck());
            return List.of(Map.of("id", 1001L, "displayName", "alice"), Map.of("id", 1003L, "displayName", "Admin"));
        });

        List<DirectoryPerson> people = withModels(true, true, () -> org.searchAllPeople("", true, 20));

        assertEquals(List.of(new DirectoryPerson(1003L, "Admin", null, null, null, true),
                new DirectoryPerson(1001L, "Alice Tan", "E001", null, null, true),
                new DirectoryPerson(1002L, "Bob Lim", "E002", null, null, false)), people);
        String filters = queries.getFirst().getFilters().toString();
        assertFalse(filters.contains("userId.status"), filters);
        assertFalse(filters.contains("searchName"), "a blank keyword lists everyone: " + filters);
        assertTrue(permissionChecksSkipped.stream().allMatch(Boolean::booleanValue), "past the caller's permissions");
    }

    @Test
    void theWholeDirectoryOfThoseWhoCanActAsksForSigningInAccountsOnly() {
        List<FlexQuery> accountSearches = new ArrayList<>();
        List<Page<Map<String, Object>>> accountPages = new ArrayList<>();
        when(modelService.searchName(anyString(), any(FlexQuery.class), any())).thenAnswer(invocation -> {
            accountSearches.add(invocation.getArgument(1));
            accountPages.add(invocation.getArgument(2));
            return List.of();
        });

        withModels(true, true, () -> org.searchAllPeople("adm", false, 20));

        assertEquals(List.of("Employee"), models, "no account found by its name: nothing more to ask");
        assertTrue(queries.getFirst().getFilters().toString().contains("[\"userId.status\",\"IN\","));
        String accounts = accountSearches.getFirst().getFilters().toString();
        assertTrue(accounts.contains("[\"status\",\"IN\","), accounts);
        assertTrue(accounts.contains("[\"searchName\",\"CONTAINS\",\"adm\"]"), accounts);
        assertEquals(20, accountPages.getFirst().getPageSize(), "the accounts asked for are the first page of the limit");
        assertEquals(1, accountPages.getFirst().getPageNumber());
    }

    @Test
    void anEmployeeModelWithoutACodeIsReadWithoutOne() {
        answers.put("Employee", List.of(employee(1001L, "Alice Tan", null, "Finance", null)));

        List<DirectoryPerson> people = withModels(false, false, () -> org.searchPeople("alice", 5));

        assertNull(people.getFirst().code());
        assertFalse(queries.getFirst().getFields().contains("code"));
        assertFalse(queries.getFirst().getFilters().toString().contains("userId.status"),
                "without an account model every account counts as signing in");
    }

    @Test
    void pickedPeopleAreNamedInTheOrderAskedOnePerAccount() {
        answers.put("Employee", List.of(employee(1001L, "Alice Tan", "E001", "Finance", null),
                employee(1002L, "Bob Lim", "E002", "Sales", null),
                employee(1001L, "Alice Tan (copy)", "E009", "Finance", null)));
        answers.put("UserAccount", List.of(Map.of("id", 1001L, "status", "Active"),
                Map.of("id", 1002L, "status", "Frozen")));

        List<DirectoryPerson> people = withModels(true, true, () -> org.getPeople(List.of(1002L, 1001L, 1003L)));

        assertEquals(List.of(new DirectoryPerson(1002L, "Bob Lim", "E002", "Sales", null, false),
                new DirectoryPerson(1001L, "Alice Tan", "E001", "Finance", null, true)), people);
        assertTrue(queries.getFirst().getFilters().toString().contains("[\"userId\",\"IN\","));
    }

    @Test
    void departmentsMatchByCodeOrNameWithTheirParentsName() {
        answers.put("Department", List.of(department(10L, "Finance", "D10", "Head Office")));

        List<DirectoryDepartment> departments = withModels(false, true, () -> org.searchDepartments("fin", 10));

        assertEquals(List.of(new DirectoryDepartment(10L, "Finance", "D10", "Head Office")), departments);
        FlexQuery query = queries.getFirst();
        assertEquals("[\"searchName\",\"CONTAINS\",\"fin\"]", query.getFilters().toString());
        assertEquals(ConvertType.REFERENCE, query.getConvertType());
        assertEquals(10, query.getLimitSize());
        assertEquals(Orders.ofAsc("name").addAsc("id").toString(), query.getOrders().toString());
        assertFalse(query.getFilterControl().isSkipActiveControl(), "a search lists open departments only");
    }

    @Test
    void pickedDepartmentsAreNamedClosedOnesIncludedInTheOrderAsked() {
        answers.put("Department", List.of(department(10L, "Finance", "D10", null), department(20L, "Sales", "D20", null)));

        List<DirectoryDepartment> departments = withModels(false, true, () -> org.getDepartments(List.of(20L, 10L, 30L)));

        assertEquals(List.of(20L, 10L), departments.stream().map(DirectoryDepartment::id).toList());
        assertTrue(queries.getFirst().getFilterControl().isSkipActiveControl());
    }

    @Test
    void everyDirectoryReadButAPersonsSearchSkipsTheCallersPermissionChecks() {
        answers.put("Employee", List.of(employee(1001L, "Alice Tan", "E001", null, null)));

        withModels(true, true, () -> {
            org.getPeople(List.of(1001L));
            org.searchDepartments(null, 20);
            return org.getDepartments(List.of(10L));
        });

        assertFalse(permissionChecksSkipped.isEmpty());
        assertTrue(permissionChecksSkipped.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void rolesAreNamedThoseAskedByIdFirstEachOncePastTheCallersPermissions() {
        answers.put("Role", List.of(Map.of("id", 7L, "code", "HR_ADMIN", "name", "HR Admin"),
                Map.of("id", 9L, "code", "MANAGER", "name", "Manager")));

        List<String> names = withModels(true, true, () -> org.getRoleNames(List.of(9L), List.of("HR_ADMIN", "MANAGER")));

        assertEquals(List.of("Manager", "HR Admin"), names);
        assertEquals(List.of("Role"), models);
        assertTrue(permissionChecksSkipped.getFirst());
    }

    /** Runs {@code read} with or without user-starter's account model, and with or without code fields. */
    private static <T> T withModels(boolean accounts, boolean codes, Supplier<T> read) {
        try (MockedStatic<ModelManager> modelManager = mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existModel("UserAccount")).thenReturn(accounts);
            modelManager.when(() -> ModelManager.existField("Employee", "code")).thenReturn(codes);
            modelManager.when(() -> ModelManager.existField("EmployeeDirectory", "code")).thenReturn(codes);
            modelManager.when(() -> ModelManager.existField("Department", "code")).thenReturn(codes);
            return read.get();
        }
    }

    /** An employee as a reference read returns it: its to-one fields as references. */
    private static Map<String, Object> employee(Long accountId, String name, String code, String department,
                                                String position) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", accountId - 1000);
        row.put("userId", ModelReference.of(accountId, "account " + accountId));
        row.put("fullName", name);
        row.put("code", code);
        row.put("departmentId", department == null ? null : ModelReference.of(10L, department));
        row.put("jobPositionId", position == null ? null : ModelReference.of(7L, position));
        return row;
    }

    private static Map<String, Object> department(Long id, String name, String code, String parent) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("code", code);
        row.put("parentId", parent == null ? null : ModelReference.of(1L, parent));
        return row;
    }
}
