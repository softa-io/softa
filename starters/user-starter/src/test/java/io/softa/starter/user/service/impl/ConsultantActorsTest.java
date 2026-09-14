package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Labelling a tenant's audit trail with who was a consultant.
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
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ConsultantServiceImpl consultantService = new ConsultantServiceImpl();

    ConsultantActorsTest() {
        ReflectionTestUtils.setField(consultantService, "accountService", accountService);
        ReflectionTestUtils.setField(consultantService, "identityService", identityService);
    }

    private static UserAccount account(Long id, Long profileId) {
        UserAccount a = new UserAccount();
        a.setId(id);
        a.setProfileId(profileId);
        return a;
    }

    private static UserIdentity identity(Long profileId, String email) {
        UserIdentity i = new UserIdentity();
        i.setProfileId(profileId);
        i.setLoginEmail(email);
        return i;
    }

    @Test
    void onlyTheConsultantActorsComeBack_eachWithTheirLoginEmail() {
        // The account query itself filters on the flag, so whatever it returns IS the consultant
        // subset — the employee ids simply never appear. The email is the PERSON's login
        // identifier (the actor column names them by it), read from the credential in one batch.
        when(accountService.searchList(any(Filters.class))).thenReturn(List.of(account(2L, 20L)));
        when(identityService.searchList(any(Filters.class))).thenReturn(List.of(identity(20L, "c@zingkey.com")));

        assertThat(consultantService.consultantActors(List.of(1L, 2L, 3L)))
                .isEqualTo(Map.of(2L, "c@zingkey.com"));
    }

    @Test
    void aConsultantWithNoCredentialRowIsStillFlagged() {
        // The flag is the load-bearing half: a missing email must not make the change unattributed.
        when(accountService.searchList(any(Filters.class))).thenReturn(List.of(account(2L, 20L)));
        when(identityService.searchList(any(Filters.class))).thenReturn(List.of());

        Map<Long, String> actors = consultantService.consultantActors(List.of(2L));
        assertThat(actors).containsKey(2L);
        assertThat(actors.get(2L)).isNull();
    }

    @Test
    void tooManyActorsAtOnceIsRefused() {
        // The audit panel asks for one page; an unbounded IN is a way to walk the account table.
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consultantService.consultantActors(tooMany))
                .hasMessageContaining("At most 500");
        verify(accountService, never()).searchList(any(Filters.class));
    }

    @Test
    void anEmptyPageAsksNothing() {
        // A log page with no actors should not spend a query to learn that.
        assertThat(consultantService.consultantActors(List.of())).isEmpty();
        assertThat(consultantService.consultantActors(null)).isEmpty();
        verify(accountService, never()).searchList(any(Filters.class));
    }
}
