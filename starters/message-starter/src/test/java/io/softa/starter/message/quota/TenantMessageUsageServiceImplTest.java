package io.softa.starter.message.quota;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.quota.entity.TenantMessageUsage;
import io.softa.starter.message.quota.service.impl.TenantMessageUsageServiceImpl;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TenantMessageUsageServiceImpl}: the optimistic-lock CAS
 * check-and-increment — first send creates the month row, later sends patch
 * only their channel's columns carrying the read version, an exhausted
 * ceiling rejects terminally, and lost races (version conflict / first-send
 * insert race) retry on a fresh read.
 */
class TenantMessageUsageServiceImplTest {

    private TenantMessageUsageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new TenantMessageUsageServiceImpl());
        // Unit tests bypass the REQUIRES_NEW proxy: self = the spy itself.
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static TenantMessageUsage row(long used, long version) {
        TenantMessageUsage row = new TenantMessageUsage();
        row.setId(900L);
        row.setTenantId(5L);
        row.setMailUsed(used);
        row.setSmsUsed(2L);
        row.setVersion(version);
        return row;
    }

    @Test
    void firstSendCreatesTheMonthRow_withSnapshotLimit() {
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));
        doReturn(1L).when(service).createOne(any(TenantMessageUsage.class));

        service.consume("mail", 5L, 100L);

        ArgumentCaptor<TenantMessageUsage> captor = ArgumentCaptor.forClass(TenantMessageUsage.class);
        verify(service).createOne(captor.capture());
        TenantMessageUsage created = captor.getValue();
        Assertions.assertEquals(5L, created.getTenantId());
        Assertions.assertEquals(1L, created.getMailUsed());
        Assertions.assertEquals(0L, created.getSmsUsed());
        Assertions.assertEquals(100L, created.getMailMonthlyLimit());
        Assertions.assertNull(created.getSmsMonthlyLimit());
        Assertions.assertNotNull(created.getMonth());
    }

    @Test
    void subsequentSendPatchesOnlyItsChannel_carryingTheReadVersion() {
        doReturn(Optional.of(row(41L, 7L))).when(service).searchOne(any(Filters.class));
        doReturn(true).when(service).updateOne(any(TenantMessageUsage.class));

        service.consume("mail", 5L, 100L);

        ArgumentCaptor<TenantMessageUsage> captor = ArgumentCaptor.forClass(TenantMessageUsage.class);
        verify(service).updateOne(captor.capture());
        TenantMessageUsage patch = captor.getValue();
        Assertions.assertEquals(900L, patch.getId());
        Assertions.assertEquals(7L, patch.getVersion());
        Assertions.assertEquals(42L, patch.getMailUsed());
        Assertions.assertEquals(100L, patch.getMailMonthlyLimit());
        // The other channel's counters are untouched by the patch.
        Assertions.assertNull(patch.getSmsUsed());
        Assertions.assertNull(patch.getSmsMonthlyLimit());
    }

    @Test
    void exhaustedCeilingRejectsTerminally() {
        doReturn(Optional.of(row(100L, 7L))).when(service).searchOne(any(Filters.class));

        Assertions.assertThrows(BusinessException.class,
                () -> service.consume("mail", 5L, 100L));
        verify(service, never()).updateOne(any(TenantMessageUsage.class));
    }

    @Test
    void zeroCeilingRejectsTheFirstSend() {
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));

        Assertions.assertThrows(BusinessException.class,
                () -> service.consume("mail", 5L, 0L));
        verify(service, never()).createOne(any(TenantMessageUsage.class));
    }

    @Test
    void nullCeilingIsUnlimited_butStillCounts() {
        doReturn(Optional.of(row(999_999L, 7L))).when(service).searchOne(any(Filters.class));
        doReturn(true).when(service).updateOne(any(TenantMessageUsage.class));

        Assertions.assertDoesNotThrow(() -> service.consume("mail", 5L, null));
        verify(service).updateOne(any(TenantMessageUsage.class));
    }

    @Test
    void versionConflictRetriesOnAFreshRead() {
        doReturn(Optional.of(row(41L, 7L)), Optional.of(row(42L, 8L)))
                .when(service).searchOne(any(Filters.class));
        doThrow(new VersionException("lost the race")).doReturn(true)
                .when(service).updateOne(any(TenantMessageUsage.class));

        service.consume("mail", 5L, 100L);

        verify(service, times(2)).updateOne(any(TenantMessageUsage.class));
        ArgumentCaptor<TenantMessageUsage> captor = ArgumentCaptor.forClass(TenantMessageUsage.class);
        verify(service, times(2)).updateOne(captor.capture());
        // The second attempt used the re-read state (42 → 43, version 8).
        Assertions.assertEquals(43L, captor.getAllValues().get(1).getMailUsed());
        Assertions.assertEquals(8L, captor.getAllValues().get(1).getVersion());
    }

    @Test
    void firstSendInsertRaceRetriesAsAnUpdate() {
        // Attempt 1: read empty → create collides (row appeared) → re-check sees
        // the row → retry. Attempt 2: read the row → increment.
        doReturn(Optional.empty(), Optional.of(row(1L, 1L)), Optional.of(row(1L, 1L)))
                .when(service).searchOne(any(Filters.class));
        doThrow(new RuntimeException("duplicate key")).when(service).createOne(any(TenantMessageUsage.class));
        doReturn(true).when(service).updateOne(any(TenantMessageUsage.class));

        service.consume("mail", 5L, 100L);

        ArgumentCaptor<TenantMessageUsage> captor = ArgumentCaptor.forClass(TenantMessageUsage.class);
        verify(service).updateOne(captor.capture());
        Assertions.assertEquals(2L, captor.getValue().getMailUsed());
    }

    @Test
    void unrelatedCreateFailurePropagates() {
        // Create fails and the re-check still sees no row: not the insert race —
        // the original failure surfaces instead of being retried away.
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));
        doThrow(new IllegalStateException("db down")).when(service).createOne(any(TenantMessageUsage.class));

        Assertions.assertThrows(IllegalStateException.class,
                () -> service.consume("mail", 5L, 100L));
    }

    @Test
    void unresolvedContentionGivesUpAsTransientAfterBoundedRetries() {
        doReturn(Optional.of(row(41L, 7L))).when(service).searchOne(any(Filters.class));
        doThrow(new VersionException("always losing")).when(service)
                .updateOne(any(TenantMessageUsage.class));

        // Not a BusinessException: the delivery processors read that as "the quota is used up" and fail
        // the record for good. Contention passes, so the send must reach the retry policy as transient.
        VersionException ex = Assertions.assertThrows(VersionException.class,
                () -> service.consume("mail", 5L, 100L));
        Assertions.assertEquals(ErrorCategory.TRANSIENT, new ErrorClassifier().classify(null, ex.getMessage()));
        verify(service, times(5)).updateOne(any(TenantMessageUsage.class));
    }
}
