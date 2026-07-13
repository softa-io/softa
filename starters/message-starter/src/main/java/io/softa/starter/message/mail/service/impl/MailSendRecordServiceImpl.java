package io.softa.starter.message.mail.service.impl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.shared.AbstractCasSendRecordServiceImpl;

/**
 * MailSendRecord service implementation.
 * <p>
 * The generic status-transition CAS (casStatus / markRetry / markFailed /
 * markDeadLetter) is inherited from {@link AbstractCasSendRecordServiceImpl};
 * this class adds the mail-specific writes (sent / bounce / read-receipt) and
 * Message-ID lookups. All transitions use the framework {@code versionLock}
 * path so duplicate broker deliveries get {@code false} back and no-op.
 */
@Service
public class MailSendRecordServiceImpl extends AbstractCasSendRecordServiceImpl<MailSendRecord>
        implements MailSendRecordService {

    @Override
    public boolean casStatus(Long id, long expectedVersion, MailSendStatus next) {
        return transitionStatus(id, expectedVersion, next);
    }

    @Override
    public Map<String, MailSendRecord> findByMessageIds(Collection<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return Map.of();
        List<String> ids = messageIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Filters filters = new Filters().in(MailSendRecord::getMessageId, ids);
        List<MailSendRecord> rows = this.searchList(filters);
        Map<String, MailSendRecord> out = new HashMap<>(rows.size() * 2);
        for (MailSendRecord r : rows) {
            if (r.getMessageId() != null) out.put(r.getMessageId(), r);
        }
        return out;
    }

    @Override
    public void markSent(Long id, long expectedVersion,
                         String messageId, String providerName) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", MailSendStatus.SENT);
        patch.put("sentAt", LocalDateTime.now());
        patch.put("messageId", messageId);
        patch.put("errorCode", null);
        patch.put("errorMessage", null);
        updateVersioned(patch);
    }

    @Override
    public boolean markBounced(Long id, long expectedVersion, String bounceCode) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("bounced", true);
        patch.put("bounceCode", bounceCode);
        patch.put("status", MailSendStatus.FAILED);
        return updateVersioned(patch);
    }

    @Override
    public boolean markReadReceiptReceived(Long id, long expectedVersion) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("readReceiptReceived", true);
        patch.put("readReceiptReceivedAt", LocalDateTime.now());
        return updateVersioned(patch);
    }

    @Override
    public boolean markRetry(Long id, long expectedVersion, String errorCode, String errorMessage,
                             LocalDateTime nextRetryAt) {
        int retryCount = getById(id)
                .map(MailSendRecord::getRetryCount)
                .orElse(0);
        return markRetryStatus(id, expectedVersion, MailSendStatus.RETRY,
                retryCount + 1, errorCode, errorMessage, nextRetryAt);
    }

    @Override
    public boolean markFailed(Long id, long expectedVersion, String errorCode, String errorMessage) {
        return markTerminalStatus(id, expectedVersion, MailSendStatus.FAILED, errorCode, errorMessage);
    }

    @Override
    public boolean markDeadLetter(Long id, long expectedVersion, String errorCode, String errorMessage) {
        return markTerminalStatus(id, expectedVersion, MailSendStatus.DEAD_LETTER, errorCode, errorMessage);
    }
}
