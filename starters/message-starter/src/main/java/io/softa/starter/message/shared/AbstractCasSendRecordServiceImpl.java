package io.softa.starter.message.shared;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.service.impl.EntityServiceImpl;

/**
 * Shared optimistic-lock transitions for the mail / sms send-record
 * services.
 * <p>
 * The status-update contract is identical across channels, so it lives here
 * once. The actual compare-and-set is delegated to the framework
 * {@code versionLock} path instead of duplicating SQL in this starter.
 * <p>
 * Channel-specific writes — {@code markSent} with provider identity, bounce /
 * read-receipt linkage — stay in the concrete subclasses, which also bind the
 * status values.
 */
public abstract class AbstractCasSendRecordServiceImpl<E extends AbstractModel>
        extends EntityServiceImpl<E, Long> {

    /**
     * Transition the status column under the framework optimistic lock. Returns
     * {@code false} when the version was superseded.
     */
    protected boolean transitionStatus(Long id, long expectedVersion, Object status) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", status);
        return updateVersioned(patch);
    }

    protected boolean markRetryStatus(Long id, long expectedVersion, Object status,
                                      int nextRetryCount, String errorCode, String errorMessage,
                                      LocalDateTime nextRetryAt) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", status);
        patch.put("retryCount", nextRetryCount);
        patch.put("errorCode", errorCode);
        patch.put("errorMessage", errorMessage);
        patch.put("nextRetryAt", nextRetryAt);
        return updateVersioned(patch);
    }

    protected boolean markTerminalStatus(Long id, long expectedVersion, Object status,
                                         String errorCode, String errorMessage) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", status);
        patch.put("errorCode", errorCode);
        patch.put("errorMessage", errorMessage);
        return updateVersioned(patch);
    }

    protected Map<String, Object> versionedPatch(Long id, long expectedVersion) {
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, id);
        patch.put(ModelConstant.VERSION, expectedVersion);
        return patch;
    }

    protected boolean updateVersioned(Map<String, Object> patch) {
        try {
            return modelService.updateOne(modelName, patch);
        } catch (VersionException e) {
            return false;
        }
    }
}
