package io.softa.starter.message.mail.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.message.mail.entity.MailFetchImapWatermark;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Lease transitions are framework versionLock CAS operations: the lease token
 * is the row version, and a superseded worker's late writes must no-op instead
 * of clobbering the new holder's lease or UID.
 */
class MailFetchImapWatermarkServiceImplTest {

    private MailFetchImapWatermarkServiceImpl service;
    private ModelService<Long> modelService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = spy(new MailFetchImapWatermarkServiceImpl());
        modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);
        ReflectionTestUtils.setField(service, "modelName", "MailFetchImapWatermark");
    }

    private MailFetchImapWatermark row(long version, LocalDateTime inProgressSince) {
        MailFetchImapWatermark w = new MailFetchImapWatermark();
        w.setId(1L);
        w.setServerConfigId(10L);
        w.setFolderName("INBOX");
        w.setLastSeenUid(100L);
        w.setInProgressSince(inProgressSince);
        w.setVersion(version);
        return w;
    }

    private Map<String, Object> capturedPatch() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(modelService).updateOne(anyString(), captor.capture());
        return captor.getValue();
    }

    // ========== tryAcquireLease ==========

    @Test
    void tryAcquireLeaseCreatesRowAndAcquiresOnFirstCall() {
        doReturn(List.of()).when(service).searchList(any(FlexQuery.class));
        MailFetchImapWatermark created = row(0L, LocalDateTime.now());
        created.setLastSeenUid(0L);
        doReturn(created).when(service).createOneAndFetch(any(MailFetchImapWatermark.class));

        Optional<MailFetchImapWatermark> result =
                service.tryAcquireLease(10L, "INBOX", Duration.ofHours(1));

        Assertions.assertTrue(result.isPresent());
        Assertions.assertNotNull(result.get().getInProgressSince());
        verify(modelService, never()).updateOne(anyString(), anyMap());
    }

    @Test
    void tryAcquireLeaseReturnsEmptyWhenLeaseHeldByActiveWorker() {
        // Fresh lease (10 min old, 1h timeout) → short-circuits before any CAS.
        doReturn(List.of(row(3L, LocalDateTime.now().minusMinutes(10))))
                .when(service).searchList(any(FlexQuery.class));

        Optional<MailFetchImapWatermark> result =
                service.tryAcquireLease(10L, "INBOX", Duration.ofHours(1));

        Assertions.assertTrue(result.isEmpty());
        verify(modelService, never()).updateOne(anyString(), anyMap());
        verify(service, never()).createOneAndFetch(any(MailFetchImapWatermark.class));
    }

    @Test
    void tryAcquireLeaseReclaimsStaleLeaseViaVersionCas() {
        doReturn(List.of(row(3L, LocalDateTime.now().minusHours(2))))
                .when(service).searchList(any(FlexQuery.class));
        when(modelService.updateOne(anyString(), anyMap())).thenReturn(true);

        Optional<MailFetchImapWatermark> result =
                service.tryAcquireLease(10L, "INBOX", Duration.ofHours(1));

        Assertions.assertTrue(result.isPresent());
        // Post-acquire version = expected + 1: the caller's lease token.
        Assertions.assertEquals(4L, result.get().getVersion());
        Assertions.assertNotNull(result.get().getInProgressSince());
        Map<String, Object> patch = capturedPatch();
        Assertions.assertEquals(3L, patch.get(ModelConstant.VERSION));
        Assertions.assertNotNull(patch.get("inProgressSince"));
    }

    @Test
    void tryAcquireLeaseLosesRace_returnsEmpty() {
        doReturn(List.of(row(3L, null)))   // idle lease, but another worker CASes first
                .when(service).searchList(any(FlexQuery.class));
        when(modelService.updateOne(anyString(), anyMap())).thenThrow(new VersionException("superseded"));

        Optional<MailFetchImapWatermark> result =
                service.tryAcquireLease(10L, "INBOX", Duration.ofHours(1));

        Assertions.assertTrue(result.isEmpty());
    }

    // ========== resetForUidValidityChange ==========

    @Test
    void resetForUidValidityChangeCasesZeroUidAndNewValidity() {
        when(modelService.updateOne(anyString(), anyMap())).thenReturn(true);

        boolean applied = service.resetForUidValidityChange(1L, 4L, 99L);

        Assertions.assertTrue(applied);
        Map<String, Object> patch = capturedPatch();
        Assertions.assertEquals(4L, patch.get(ModelConstant.VERSION));
        Assertions.assertEquals(0L, patch.get("lastSeenUid"));
        Assertions.assertEquals(99L, patch.get("uidValidity"));
    }

    // ========== releaseSuccess ==========

    @Test
    void releaseSuccessAdvancesUidAndClearsLeaseInOneCas() {
        when(modelService.updateOne(anyString(), anyMap())).thenReturn(true);

        boolean applied = service.releaseSuccess(1L, 5L, 200L);

        Assertions.assertTrue(applied);
        Map<String, Object> patch = capturedPatch();
        Assertions.assertEquals(5L, patch.get(ModelConstant.VERSION));
        Assertions.assertEquals(200L, patch.get("lastSeenUid"));
        Assertions.assertTrue(patch.containsKey("inProgressSince"));
        Assertions.assertNull(patch.get("inProgressSince"));
        Assertions.assertNotNull(patch.get("lastFetchedAt"));
    }

    @Test
    void releaseSuccessSkipsUidAdvanceWhenMaxIsNullOrZero() {
        when(modelService.updateOne(anyString(), anyMap())).thenReturn(true);

        service.releaseSuccess(1L, 5L, null);
        service.releaseSuccess(1L, 6L, 0L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(modelService, times(2)).updateOne(anyString(), captor.capture());
        for (Map<String, Object> patch : captor.getAllValues()) {
            Assertions.assertFalse(patch.containsKey("lastSeenUid"),
                    "UID must not be touched when maxUidProcessed is null/0");
        }
    }

    @Test
    void releaseSuccessSuperseded_returnsFalseAndDoesNotThrow() {
        // A stale worker finishing after a takeover must no-op, not clobber
        // the new holder's lease or roll back its UID.
        when(modelService.updateOne(anyString(), anyMap())).thenThrow(new VersionException("superseded"));

        boolean applied = service.releaseSuccess(1L, 5L, 200L);

        Assertions.assertFalse(applied);
    }

    // ========== releaseFailure ==========

    @Test
    void releaseFailureClearsLeaseWithoutAdvancingUid() {
        when(modelService.updateOne(anyString(), anyMap())).thenReturn(true);

        boolean applied = service.releaseFailure(1L, 5L, "IMAP connection refused");

        Assertions.assertTrue(applied);
        Map<String, Object> patch = capturedPatch();
        Assertions.assertTrue(patch.containsKey("inProgressSince"));
        Assertions.assertNull(patch.get("inProgressSince"));
        Assertions.assertFalse(patch.containsKey("lastSeenUid"));
        Assertions.assertFalse(patch.containsKey("lastFetchedAt"));
    }
}
