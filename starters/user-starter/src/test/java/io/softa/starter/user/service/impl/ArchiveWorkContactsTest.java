package io.softa.starter.user.service.impl;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.dto.WorkContacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading the work contacts from the employee record (S-B / D23).
 *
 * <p>Where they are read from is the whole point. Reset User notifies the address it is REPLACING,
 * so the account has to still hold the old value when it runs — which is only true because nothing
 * pushes the record's value onto the account ahead of time. Read the new value from the record and
 * the old one from the account, at the same moment, and both are available; propagate on the
 * record's own edit instead and the warning meant for whoever still holds the old address goes to
 * the new one.
 *
 * <p>Every miss degrades to "no record" rather than throwing: the operations that ask refuse on
 * their own, with a message naming the record, which beats a stack trace about a model the caller
 * never mentioned.
 */
class ArchiveWorkContactsTest {

    private static final Long ACCOUNT = 100L;

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final UserAccountServiceImpl accountService = new UserAccountServiceImpl();
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    /** The Employee model exists only in an HR deployment, and the read is skipped without it. */
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void anHrDeployment() {
        ReflectionTestUtils.setField(accountService, "modelService", modelService);
        modelManager = org.mockito.Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
    }

    @AfterEach
    void closeStatic() {
        modelManager.close();
    }

    private void archiveRow(Map<String, Object> row) {
        when(modelService.searchOne(anyString(), any(FlexQuery.class)))
                .thenReturn(Optional.ofNullable(row));
    }

    @Test
    void bothContactsComeFromTheRecord() {
        archiveRow(Map.of("workEmail", "ada@acme.com", "workPhone", "+6591234567"));

        WorkContacts contacts = accountService.archiveWorkContacts(ACCOUNT);

        assertThat(contacts.email()).isEqualTo("ada@acme.com");
        assertThat(contacts.mobile()).isEqualTo("+6591234567");
        assertThat(contacts.any()).isTrue();
    }

    @Test
    void aBlankFieldReadsAsAbsent() {
        // So a caller checks one thing — any() — instead of every field for blankness, and a record
        // with "  " in it cannot be mistaken for a reachable channel.
        archiveRow(Map.of("workEmail", "   ", "workPhone", "+6591234567"));

        WorkContacts contacts = accountService.archiveWorkContacts(ACCOUNT);

        assertThat(contacts.email()).isNull();
        assertThat(contacts.mobile()).isEqualTo("+6591234567");
    }

    @Test
    void aRecordWithNeitherContactIsNotReachable() {
        archiveRow(Map.of("workEmail", " ", "workPhone", ""));

        assertThat(accountService.archiveWorkContacts(ACCOUNT).any()).isFalse();
    }

    @Test
    void anAccountWithNoRecordBehindIt() {
        archiveRow(null);

        assertThat(accountService.archiveWorkContacts(ACCOUNT).any()).isFalse();
    }

    @Test
    void aFailedReadDegradesRatherThanThrows() {
        when(modelService.searchOne(anyString(), any(FlexQuery.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThat(accountService.archiveWorkContacts(ACCOUNT).any()).isFalse();
    }

    @Test
    void noUserIdIsNotAnError() {
        assertThat(accountService.archiveWorkContacts(null).any()).isFalse();
    }

    @Test
    void aDeploymentWithNoEmployeeModelReadsNothing() {
        // Not every app running this starter is an HR one. Without the guard the read would fail
        // on a model that was never meant to be there.
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(false);

        assertThat(accountService.archiveWorkContacts(ACCOUNT).any()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(modelService);
    }
}
