package io.softa.starter.flow.service.impl;

import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * The directory the pickers read: searches go to the organization past the caller's permissions, within a bounded
 * size; picked people are named in the order asked — an employee as the organization shows them, an account without
 * an employee record by its name, with whether it can act.
 */
class FlowDirectoryServiceImplTest {

    private final OrganizationService organization = mock(OrganizationService.class);
    private final FlowUserNames userNames = mock(FlowUserNames.class);
    private FlowDirectoryServiceImpl directory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OrganizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(organization);
        directory = new FlowDirectoryServiceImpl(provider, userNames);
    }

    @Test
    void aSearchAsksTheOrganizationPastTheCallersPermissionsTwentyByDefault() {
        boolean[] skipped = {false};
        when(organization.searchPeople("ann", false, 20)).thenAnswer(invocation -> {
            skipped[0] = ContextHolder.getContext().isSkipPermissionCheck();
            return List.of(new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, true));
        });

        assertEquals(1, directory.searchPeople("ann", false, null).size());
        assertTrue(skipped[0]);

        directory.searchDepartments("fin", 50);
        verify(organization).searchDepartments("fin", 50);
    }

    @Test
    void aSearchReturnsOneToFiftyRows() {
        assertThrows(RuntimeException.class, () -> directory.searchPeople("a", false, 0));
        assertThrows(RuntimeException.class, () -> directory.searchDepartments("a", 51));
        verifyNoInteractions(organization);
    }

    @Test
    void pickedPeopleAreNamedInTheOrderAskedEmployeesAsTheOrganizationShowsThem() {
        DirectoryPerson ann = new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, true);
        when(organization.getPeople(List.of(3L, 1L, 2L, 9L))).thenReturn(List.of(ann));
        // A tenant administrator without an employee record, an account that left, and an id naming nobody.
        when(userNames.namesOf(List.of("3", "2", "9"))).thenReturn(Map.of("3", "Tenant Admin", "2", "old.user"));
        Map<Long, String> unusable = new HashMap<>();
        unusable.put(2L, "Deactivated");
        unusable.put(9L, null);
        when(organization.getUnusableAccounts(List.of(3L, 2L, 9L))).thenReturn(unusable);

        List<DirectoryPerson> people = directory.getPeople(List.of(3L, 1L, 3L, 2L, 9L));

        assertEquals(List.of(new DirectoryPerson(3L, "Tenant Admin", null, null, null, true), ann,
                new DirectoryPerson(2L, "old.user", null, null, null, false)), people);
    }

    @Test
    void namingEveryoneAskedAboutAsksNothingMore() {
        DirectoryPerson ann = new DirectoryPerson(1L, "Ann Lee", "E001", null, null, true);
        when(organization.getPeople(List.of(1L))).thenReturn(List.of(ann));

        assertEquals(List.of(ann), directory.getPeople(List.of(1L)));
        verifyNoInteractions(userNames);
        verify(organization, never()).getUnusableAccounts(anyCollection());
    }

    @Test
    void atMostTwoHundredIdsAreNamedAtATime() {
        List<Long> ids = LongStream.rangeClosed(1, 201).boxed().toList();

        assertThrows(RuntimeException.class, () -> directory.getPeople(ids));
        assertThrows(RuntimeException.class, () -> directory.getDepartments(ids));
        assertEquals(List.of(), directory.getPeople(null));
        verifyNoInteractions(organization);
    }
}
