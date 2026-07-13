package io.softa.starter.message.mail.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.MailFetchImapWatermark;
import io.softa.starter.message.mail.service.MailFetchImapWatermarkService;

/**
 * Watermark + lease service. Every transition is a framework {@code versionLock}
 * CAS: the lease token is the row version, bumped by exactly one per successful
 * transition. A superseded worker (stale-lease takeover) fails its late CAS and
 * no-ops instead of clearing the new holder's lease or rolling its UID.
 */
@Slf4j
@Service
public class MailFetchImapWatermarkServiceImpl
        extends EntityServiceImpl<MailFetchImapWatermark, Long>
        implements MailFetchImapWatermarkService {

    @Override
    public Optional<MailFetchImapWatermark> tryAcquireLease(Long serverConfigId, String folderName,
                                                            Duration leaseTimeout) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(leaseTimeout);

        Optional<MailFetchImapWatermark> existing = findByConfigAndFolder(serverConfigId, folderName);
        if (existing.isEmpty()) {
            MailFetchImapWatermark fresh = new MailFetchImapWatermark();
            fresh.setServerConfigId(serverConfigId);
            fresh.setFolderName(folderName);
            fresh.setLastSeenUid(0L);
            fresh.setInProgressSince(now);
            fresh.setVersion(0L);
            try {
                MailFetchImapWatermark created = createOneAndFetch(fresh);
                return Optional.ofNullable(created);
            } catch (DuplicateKeyException race) {
                // Another worker created the row first; re-read and CAS below.
                existing = findByConfigAndFolder(serverConfigId, folderName);
                if (existing.isEmpty()) {
                    return Optional.empty();
                }
            }
        }

        MailFetchImapWatermark row = existing.get();
        LocalDateTime held = row.getInProgressSince();
        if (held != null && held.isAfter(cutoff)) {
            log.debug("Lease busy for config={} folder={} — skipping this tick",
                    serverConfigId, folderName);
            return Optional.empty();
        }

        // Version CAS: only one worker flips NULL/stale → now. The condition was
        // checked above, but the version guard is what makes the claim atomic —
        // a concurrent claimer bumped the version and this update no-ops.
        long expectedVersion = version(row);
        Map<String, Object> patch = versionedPatch(row.getId(), expectedVersion);
        patch.put("inProgressSince", now);
        if (!updateVersioned(patch)) {
            log.debug("Lease lost race for config={} folder={} — skipping this tick",
                    serverConfigId, folderName);
            return Optional.empty();
        }
        row.setInProgressSince(now);
        row.setVersion(expectedVersion + 1);
        return Optional.of(row);
    }

    @Override
    public boolean resetForUidValidityChange(Long id, long expectedVersion, Long newUidValidity) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("lastSeenUid", 0L);
        patch.put("uidValidity", newUidValidity);
        return updateVersioned(patch);
    }

    @Override
    public boolean releaseSuccess(Long id, long expectedVersion, Long maxUidProcessed) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("inProgressSince", null);
        patch.put("lastFetchedAt", LocalDateTime.now());
        if (maxUidProcessed != null && maxUidProcessed > 0) {
            patch.put("lastSeenUid", maxUidProcessed);
        }
        boolean applied = updateVersioned(patch);
        if (!applied) {
            log.warn("Watermark id={} release skipped — lease was superseded (stale takeover); "
                    + "UID not advanced, new holder refetches and Message-ID dedup absorbs overlaps", id);
        }
        return applied;
    }

    @Override
    public boolean releaseFailure(Long id, long expectedVersion, String errorMessage) {
        // Failure detail goes to the application log (with full stack trace at the
        // call site). The watermark only tracks the lease — releasing it lets
        // another worker retry on the next cron tick.
        log.warn("Mail fetch failed for watermark id={}: {}", id, errorMessage);
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("inProgressSince", null);
        boolean applied = updateVersioned(patch);
        if (!applied) {
            log.debug("Watermark id={} failure-release skipped — lease already superseded", id);
        }
        return applied;
    }

    private Optional<MailFetchImapWatermark> findByConfigAndFolder(Long serverConfigId, String folderName) {
        Filters f = new Filters()
                .eq(MailFetchImapWatermark::getServerConfigId, serverConfigId)
                .eq(MailFetchImapWatermark::getFolderName, folderName);
        List<MailFetchImapWatermark> rows = searchList(new FlexQuery(f));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static long version(MailFetchImapWatermark row) {
        return row.getVersion() != null ? row.getVersion() : 0L;
    }

    private Map<String, Object> versionedPatch(Long id, long expectedVersion) {
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, id);
        patch.put(ModelConstant.VERSION, expectedVersion);
        return patch;
    }

    private boolean updateVersioned(Map<String, Object> patch) {
        try {
            return modelService.updateOne(modelName, patch);
        } catch (VersionException e) {
            return false;
        }
    }
}
