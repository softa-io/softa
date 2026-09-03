package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Set;

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
 * Labelling a tenant's audit trail with who was a consultant (PRD §4.4).
 *
 * <p>The point of asking it here rather than carrying a flag on the audit record: change logging is
 * generic — it captures whoever acted, for every model, and has no business knowing consultants
 * exist. This is the consultant-shaped question, asked by the screen that needs it.
 *
 * <p>It also deliberately reads past the roster's consultant hiding. A tenant may not administer
 * these memberships, but attribution is not administration: without this the log would show a
 * change with no author.
 */
class ConsultantActorsTest {

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final ConsultantServiceImpl consultantService = new ConsultantServiceImpl();

    ConsultantActorsTest() {
        ReflectionTestUtils.setField(consultantService, "accountService", accountService);
    }

    private static UserAccount account(Long id) {
        UserAccount a = new UserAccount();
        a.setId(id);
        return a;
    }

    @Test
    void onlyTheConsultantActorsComeBack() {
        // The query itself filters on the flag, so whatever it returns IS the consultant subset —
        // the employee ids simply never appear in the answer.
        when(accountService.searchList(any(Filters.class))).thenReturn(List.of(account(2L)));

        assertThat(consultantService.consultantActors(List.of(1L, 2L, 3L))).isEqualTo(Set.of(2L));
    }

    @Test
    void anEmptyPageAsksNothing() {
        // A log page with no actors should not spend a query to learn that.
        assertThat(consultantService.consultantActors(List.of())).isEmpty();
        assertThat(consultantService.consultantActors(null)).isEmpty();
        verify(accountService, never()).searchList(any(Filters.class));
    }
}
