package io.softa.starter.flow.service.impl;

import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.runtime.TestActorAccounts;
import io.softa.starter.flow.runtime.engine.ActorAccounts;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * The directory the pickers read, within a bounded size. A person's search goes to the organization with their own
 * permissions and says nothing of who cannot act; the designer's and the operations console's go past them and say it.
 * Picked people are named in the order asked — an employee as the organization shows them, an account without an
 * employee record by its name — whether they can act said only to the designer and the console.
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
    void aPersonsSearchAsksTheOrganizationAsThemTwentyByDefaultSayingNothingOfWhoCannotAct() {
        boolean[] skipped = {true};
        when(organization.searchPeople("ann", 20)).thenAnswer(invocation -> {
            skipped[0] = ContextHolder.existContext() && ContextHolder.getContext().isSkipPermissionCheck();
            return List.of(new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, true));
        });

        assertEquals(List.of(new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, null)),
                directory.searchPeople("ann", null));
        assertFalse(skipped[0], "with the caller's own permissions");

        directory.searchDepartments("fin", 50);
        verify(organization).searchDepartments("fin", 50);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPickerChoosingWhomToHandATaskToDecide_findsOnlyThoseAllowedToApprove() {
        when(organization.searchPeople("a", 20)).thenReturn(List.of(
                new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, true),
                new DirectoryPerson(2L, "Bob Wu", "E002", "Finance", null, true),
                new DirectoryPerson(3L, "Cat Ng", "E003", "Finance", null, true)));
        ObjectProvider<OrganizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(organization);
        ObjectProvider<ActorAccounts> accounts = mock(ObjectProvider.class);
        // Bob holds no role that approves.
        when(accounts.getIfAvailable()).thenReturn(TestActorAccounts.of(Map.of(), Map.of(), Set.of(2L)));
        FlowDirectoryServiceImpl withAccounts = new FlowDirectoryServiceImpl(provider, userNames, accounts);

        assertEquals(List.of(1L, 3L), withAccounts.searchPeople("a", null, true).stream()
                .map(DirectoryPerson::id).toList());
        // Every other picker — a CC, a filter by submitter — still finds everyone who signs in.
        assertEquals(List.of(1L, 2L, 3L), withAccounts.searchPeople("a", null).stream()
                .map(DirectoryPerson::id).toList());
    }

    @Test
    void theWholeDirectoryIsAskedPastTheCallersPermissions() {
        boolean[] skipped = {false};
        when(organization.searchAllPeople("ann", true, 20)).thenAnswer(invocation -> {
            skipped[0] = ContextHolder.getContext().isSkipPermissionCheck();
            return List.of(new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, false));
        });

        assertEquals(List.of(new DirectoryPerson(1L, "Ann Lee", "E001", "Finance", null, false)),
                directory.searchAllPeople("ann", true, null));
        assertTrue(skipped[0]);
    }

    /** A picker asks again for more as its list is scrolled — 20, 40, and so on — up to two hundred. */
    @Test
    void aSearchReturnsOneToTwoHundredRows() {
        directory.searchDepartments("fin", 200);
        verify(organization).searchDepartments("fin", 200);
        reset(organization);

        assertThrows(RuntimeException.class, () -> directory.searchPeople("a", 0));
        assertThrows(RuntimeException.class, () -> directory.searchAllPeople("a", false, 201));
        assertThrows(RuntimeException.class, () -> directory.searchDepartments("a", 201));
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

        List<DirectoryPerson> people = directory.getAllPeople(List.of(3L, 1L, 3L, 2L, 9L));

        assertEquals(List.of(new DirectoryPerson(3L, "Tenant Admin", null, null, null, true), ann,
                new DirectoryPerson(2L, "old.user", null, null, null, false)), people);
        // Named for anyone else too, without saying who cannot act.
        assertEquals(List.of(new DirectoryPerson(3L, "Tenant Admin", null, null, null, null), ann.withoutActive(),
                new DirectoryPerson(2L, "old.user", null, null, null, null)), directory.getPeople(List.of(3L, 1L, 2L, 9L)));
    }

    @Test
    void namingEveryoneAskedAboutAsksNothingMore() {
        DirectoryPerson ann = new DirectoryPerson(1L, "Ann Lee", "E001", null, null, true);
        when(organization.getPeople(List.of(1L))).thenReturn(List.of(ann));

        assertEquals(List.of(ann), directory.getAllPeople(List.of(1L)));
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
