package io.softa.starter.metadata.service.impl;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.MetadataUpgradeHistory;
import io.softa.starter.metadata.enums.MetadataUpgradeStatus;
import io.softa.starter.metadata.service.MetadataUpgradeHistoryService;

/**
 * MetadataUpgradeHistory Service Implementation.
 * <p>
 * Every {@code mark*} method is annotated {@code REQUIRES_NEW} so the history row
 * commits even when the caller's surrounding transaction (the upgrade itself) rolls
 * back. This is what makes the table a reliable record of every dispatched upgrade,
 * not just the ones that succeeded.
 */
@Slf4j
@Service
public class MetadataUpgradeHistoryServiceImpl
        extends EntityServiceImpl<MetadataUpgradeHistory, Long>
        implements MetadataUpgradeHistoryService {

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markRunning(String callbackToken, Long envId, JsonNode packageSummary) {
        Assert.notBlank(callbackToken, "callbackToken is required.");
        if (findByToken(callbackToken) != null) {
            // Studio retried the dispatch after we already received the first attempt.
            // The original upgrade's outcome is the canonical one — leave it in place.
            return false;
        }
        MetadataUpgradeHistory history = new MetadataUpgradeHistory();
        history.setCallbackToken(callbackToken);
        history.setEnvId(envId);
        history.setStatus(MetadataUpgradeStatus.RUNNING);
        history.setStartTime(LocalDateTime.now());
        history.setPackageSummary(packageSummary);
        try {
            this.createOne(history);
            return true;
        } catch (DuplicateKeyException e) {
            // Lost the race against a concurrent dispatch with the same token. The
            // other thread owns the row; treat as replay.
            return false;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markSuccess(String callbackToken, double durationSeconds) {
        Assert.notBlank(callbackToken, "callbackToken is required.");
        MetadataUpgradeHistory history = findByToken(callbackToken);
        Assert.notNull(history, "No MetadataUpgradeHistory row exists for token {0}", callbackToken);
        history.setStatus(MetadataUpgradeStatus.SUCCESS);
        history.setEndTime(LocalDateTime.now());
        history.setDurationTime(durationSeconds);
        history.setErrorMessage(null);
        this.updateOne(history);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailure(String callbackToken, String errorMessage, double durationSeconds) {
        Assert.notBlank(callbackToken, "callbackToken is required.");
        MetadataUpgradeHistory history = findByToken(callbackToken);
        Assert.notNull(history, "No MetadataUpgradeHistory row exists for token {0}", callbackToken);
        history.setStatus(MetadataUpgradeStatus.FAILURE);
        history.setEndTime(LocalDateTime.now());
        history.setDurationTime(durationSeconds);
        history.setErrorMessage(errorMessage);
        this.updateOne(history);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordReloadWarning(String callbackToken, String warningMessage) {
        Assert.notBlank(callbackToken, "callbackToken is required.");
        MetadataUpgradeHistory history = findByToken(callbackToken);
        if (history == null) {
            log.warn("Cannot record reload warning — no MetadataUpgradeHistory row for token={}", callbackToken);
            return;
        }
        if (history.getStatus() != MetadataUpgradeStatus.SUCCESS) {
            log.warn("Skip recording reload warning on token={} — row is in {} state, not SUCCESS",
                    callbackToken, history.getStatus());
            return;
        }
        String prior = history.getErrorMessage();
        String warning = "[reload warning at " + LocalDateTime.now() + "] " + warningMessage;
        history.setErrorMessage(prior == null || prior.isBlank() ? warning : prior + " | " + warning);
        this.updateOne(history);
    }

    @Override
    public MetadataUpgradeHistory findByToken(String callbackToken) {
        Assert.notBlank(callbackToken, "callbackToken is required.");
        Filters filter = new Filters().eq(MetadataUpgradeHistory::getCallbackToken, callbackToken);
        return this.searchOne(filter).orElse(null);
    }
}
