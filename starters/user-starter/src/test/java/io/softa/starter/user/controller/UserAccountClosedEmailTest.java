package io.softa.starter.user.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A closed account's Email column shows the address it had when it closed: closing clears the
 * enterprise email itself, so the next hire can be given the mailbox, and keeps a copy for the list.
 */
class UserAccountClosedEmailTest {

    private final UserAccountService service = mock(UserAccountService.class);
    private final UserAccountController controller = new UserAccountController();

    UserAccountClosedEmailTest() {
        ReflectionTestUtils.setField(controller, "service", service);
    }

    private static Map<String, Object> row(Object id, String email) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("email", email);
        return row;
    }

    @Test
    void aClosedRow_showsTheEmailItClosedWith_andOpenRowsAreLeftAlone() {
        UserAccount closed = new UserAccount();
        closed.setId(2L);
        closed.setClosedEmail("ada@acme.com");
        when(service.searchList(any(Filters.class))).thenReturn(List.of(closed));
        Map<String, Object> open = row("1", "bob@acme.com");
        Map<String, Object> deactivated = row("2", null);

        ReflectionTestUtils.invokeMethod(controller, "showClosedEmail", new ArrayList<>(List.of(open, deactivated)));

        assertThat(deactivated.get("email")).isEqualTo("ada@acme.com");
        assertThat(open.get("email")).isEqualTo("bob@acme.com");
    }

    @Test
    void aReadThatDidNotAskForTheEmail_isNotWidened() {
        Map<String, Object> noEmail = new HashMap<>(Map.of("id", 2L));

        ReflectionTestUtils.invokeMethod(controller, "showClosedEmail", new ArrayList<>(List.of(noEmail)));

        assertThat(noEmail).doesNotContainKey("email");
        verify(service, never()).searchList(any(Filters.class));
    }
}
