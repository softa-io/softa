package io.softa.starter.message.mail.service;

import java.time.Duration;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailFetchImapWatermark;

/**
 * Per-(server_config, folder) IMAP UID watermark with lease semantics.
 * <p>
 * Cron consumers call {@link #tryAcquireLease} before fetching; another worker
 * holding an active lease causes acquisition to fail and the cron to skip this
 * (config, folder) until the next tick. On completion the lease is released
 * via {@link #releaseSuccess} or {@link #releaseFailure}.
 * <p>
 * Concurrency safety is the framework optimistic lock ({@code versionLock}):
 * every transition — acquire, UID reset, release — is a version-guarded CAS.
 * The version returned by {@link #tryAcquireLease} is the worker's lease token;
 * each subsequent successful CAS bumps it by exactly one, so the caller tracks
 * it locally. A worker superseded by a stale-lease takeover fails its late
 * CAS ({@code false}) instead of clobbering the new holder's lease or UID.
 */
public interface MailFetchImapWatermarkService extends EntityService<MailFetchImapWatermark, Long> {

    /**
     * Try to claim the lease for {@code (serverConfigId, folderName)} via a
     * version CAS on {@code inProgressSince}. Creates the row on first-ever
     * call. The returned row's {@code version} reflects the post-acquire value —
     * the caller must pass it (incrementing after each successful transition it
     * performs) to {@link #resetForUidValidityChange} / {@link #releaseSuccess} /
     * {@link #releaseFailure}.
     *
     * @param serverConfigId mail receive server config ID
     * @param folderName     IMAP folder name
     * @param leaseTimeout   how long since {@code in_progress_since} qualifies
     *                       as stale (worker presumed crashed)
     * @return the watermark row with the lease acquired, or empty if held elsewhere
     */
    Optional<MailFetchImapWatermark> tryAcquireLease(Long serverConfigId, String folderName,
                                                    Duration leaseTimeout);

    /**
     * Reset {@code last_seen_uid} to 0 and update {@code uid_validity} under the
     * lease version. Called when the IMAP server reports a different
     * {@code UIDVALIDITY} than previously observed (mailbox rebuild).
     *
     * @return {@code true} if applied (version bumped by one); {@code false}
     *         when the lease was superseded — the caller must abandon the fetch
     */
    boolean resetForUidValidityChange(Long id, long expectedVersion, Long newUidValidity);

    /**
     * Release the lease and advance {@code last_seen_uid} in one CAS under the
     * lease version. Monotonic progress is guaranteed by the version guard:
     * only the current lease holder can write, so no separate UID comparison
     * is needed.
     *
     * @param maxUidProcessed highest UID successfully processed in this batch;
     *                        null or 0 → only release the lease, leave UID untouched
     * @return {@code true} if applied; {@code false} when superseded (the new
     *         holder refetches the range — Message-ID dedup absorbs overlaps)
     */
    boolean releaseSuccess(Long id, long expectedVersion, Long maxUidProcessed);

    /**
     * Release the lease without advancing the UID, under the lease version.
     * Used when the fetch batch failed before completing.
     *
     * @return {@code true} if applied; {@code false} when superseded
     */
    boolean releaseFailure(Long id, long expectedVersion, String errorMessage);
}
