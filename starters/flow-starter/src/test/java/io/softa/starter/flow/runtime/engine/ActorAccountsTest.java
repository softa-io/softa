package io.softa.starter.flow.runtime.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.starter.flow.runtime.TestActorAccounts;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Who can act in an approval: only a user whose account signs in. Resolved people who cannot are dropped, keeping
 * the others' order; a person an action names is refused, named, with why — every reason a status gives.
 */
class ActorAccountsTest {

    private static final Map<String, String> NAMES = Map.of("1001", "Ann Lee", "1002", "Bob Wu", "1003", "Cat Ng",
            "1004", "Dan Ho", "1005", "Eve Yu", "1006", "Fay Li", "1007", "Gus Ma");

    private final ActorAccounts accounts = TestActorAccounts.of(statuses(), NAMES);

    /** 1002 frozen, 1003 never activated, 1004 invited, 1005 locked, 1006 gone, 1007 an application's own; 1099 none. */
    private static Map<Long, String> statuses() {
        Map<Long, String> statuses = new LinkedHashMap<>();
        statuses.put(1002L, "Frozen");
        statuses.put(1003L, "Pending");
        statuses.put(1004L, "Invited");
        statuses.put(1005L, "Locked");
        statuses.put(1006L, "Deactivated");
        statuses.put(1007L, "Suspended");
        statuses.put(1099L, null);
        return statuses;
    }

    @Test
    void peopleWhoCannotActAreDroppedAndTheOthersKeepTheirOrder() {
        assertEquals(List.of("2001", "1001", "approver-x"),
                accounts.usable(List.of("2001", "1002", "1001", "1099", "approver-x")),
                "an id that is no account id can act");
    }

    @Test
    void eachReasonNamesThePerson() {
        Map<String, String> why = accounts.whyUnusable(List.of("1001", "1002", "1003", "1004", "1005", "1006", "1007",
                "1099"));

        assertEquals(List.of("1002", "1003", "1004", "1005", "1006", "1007", "1099"), List.copyOf(why.keySet()));
        assertEquals("Bob Wu's account is frozen", why.get("1002"));
        assertEquals("Cat Ng's account has not been activated yet", why.get("1003"));
        assertEquals("Dan Ho has not accepted the invitation to their account yet", why.get("1004"));
        assertEquals("Eve Yu's account is locked", why.get("1005"));
        assertEquals("Fay Li's account is deactivated", why.get("1006"));
        assertEquals("Gus Ma's account is suspended", why.get("1007"));
        assertEquals("1099 has no account", why.get("1099"), "a user without a name is named by their id");
    }

    @Test
    void aRefusalNamesEveryoneWhoCannotAct() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> accounts.requireUsable(List.of("1001", "1002", "1099")));

        assertEquals("Bob Wu's account is frozen; 1099 has no account.", refused.getMessage());
        assertDoesNotThrow(() -> accounts.requireUsable(List.of("1001", "approver-x")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnOrganizationServiceEveryoneCanAct() {
        ActorAccounts none = new ActorAccounts(mock(ObjectProvider.class), mock(ObjectProvider.class));

        assertEquals(List.of("1002", "1099"), none.usable(List.of("1002", "1099")));
        assertEquals(Map.of(), none.whyUnusable(List.of("1002")));
        assertEquals(Map.of("1002", "1002"), new HashMap<>(none.namesOf(List.of("1002"))));
    }
}
