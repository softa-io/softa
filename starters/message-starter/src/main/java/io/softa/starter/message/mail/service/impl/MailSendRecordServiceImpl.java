package io.softa.starter.message.mail.service.impl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.enums.MailServerOwner;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.shared.AbstractCasSendRecordServiceImpl;
import io.softa.starter.message.shared.TenantScopes;

/**
 * MailSendRecord service implementation.
 * <p>
 * The generic status-transition CAS (markSending / markRetry / markFailed /
 * markDeadLetter) is inherited from {@link AbstractCasSendRecordServiceImpl};
 * this class adds the mail-specific writes (sent / bounce / read-receipt) and
 * Message-ID lookups. All transitions use the framework {@code versionLock}
 * path, so a transition that lost a race gets {@code false} back and no-op.
 */
@Service
public class MailSendRecordServiceImpl extends AbstractCasSendRecordServiceImpl<MailSendRecord>
        implements MailSendRecordService {

    private static final String MANUAL_RETRY_CODE = "MANUAL_RETRY";
    private static final String MANUAL_RETRY_MESSAGE = "Manually requeued for delivery";

    @Override
    public boolean markSending(Long id, long expectedVersion) {
        return claimForAttempt(id, expectedVersion, MailSendStatus.SENDING);
    }

    @Override
    public boolean retry(Long id) {
        return retry(getById(id).orElseThrow(() ->
                new BusinessException("Mail send record {0} does not exist.", id)));
    }

    @Override
    public boolean retryVisible(Long id) {
        // A record the caller may not see answers like one that does not exist.
        return retry(findVisibleById(id).orElseThrow(() ->
                new BusinessException("Mail send record {0} does not exist.", id)));
    }

    @Override
    public Optional<MailSendRecord> findVisibleById(Long id) {
        return getById(id).filter(record -> TenantScopes.visibleToCurrentCaller(record.getTenantId()));
    }

    private boolean retry(MailSendRecord record) {
        MailSendStatus status = record.getStatus();
        if (status == MailSendStatus.SENT) {
            throw new BusinessException("This email was already sent — there is nothing to retry.");
        }
        if (status == MailSendStatus.SENDING) {
            throw new BusinessException("This email is being sent right now. If it is stuck, "
                    + "stale SENDING records are requeued automatically after the zombie window.");
        }
        if (!requeue(record)) {
            throw new BusinessException("The record changed concurrently — refresh and retry again.");
        }
        return true;
    }

    @Override
    public int retryByIds(List<Long> ids) {
        return retryAll(getByIds(ids));
    }

    @Override
    public int retryVisibleByIds(List<Long> ids) {
        return retryAll(getByIds(ids).stream()
                .filter(record -> TenantScopes.visibleToCurrentCaller(record.getTenantId()))
                .toList());
    }

    private int retryAll(List<MailSendRecord> records) {
        int requeued = 0;
        for (MailSendRecord record : records) {
            MailSendStatus status = record.getStatus();
            if (status != MailSendStatus.SENT && status != MailSendStatus.SENDING && requeue(record)) {
                requeued++;
            }
        }
        return requeued;
    }

    /**
     * Due now: the delivery loop picks it up on its next poll. retryCount keeps counting — a
     * manual retry grants ONE new attempt and a failure returns the record to FAILED /
     * DEAD_LETTER instead of silently re-arming the whole automatic retry budget.
     * {@code false} when the record changed since it was read.
     */
    private boolean requeue(MailSendRecord record) {
        long expectedVersion = record.getVersion() != null ? record.getVersion() : 0L;
        return markRetry(record.getId(), expectedVersion, MANUAL_RETRY_CODE, MANUAL_RETRY_MESSAGE,
                LocalDateTime.now());
    }

    @Override
    public Map<String, MailSendRecord> findByMessageIds(Collection<String> messageIds, MailServerOwner carriedBy) {
        if (messageIds == null || messageIds.isEmpty()) return Map.of();
        List<String> ids = messageIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Filters filters = new Filters()
                .in(MailSendRecord::getMessageId, ids)
                .eq(MailSendRecord::getServerOwner, carriedBy);
        if (carriedBy == MailServerOwner.TENANT && TenantScopes.multiTenancyEnabled()) {
            // A tenant's receive server answers for its own mail only — the table holds every tenant's.
            filters.eq(MailSendRecord::getTenantId, TenantScopes.currentTenantOrPlatform());
        }
        List<MailSendRecord> rows = this.searchList(filters);
        Map<String, MailSendRecord> out = new HashMap<>(rows.size() * 2);
        for (MailSendRecord r : rows) {
            if (r.getMessageId() != null) out.put(r.getMessageId(), r);
        }
        return out;
    }

    @Override
    public boolean markPrepared(MailSendRecord record, long expectedVersion) {
        Map<String, Object> patch = versionedPatch(record.getId(), expectedVersion);
        patch.put("subject", record.getSubject());
        patch.put("bodyHtml", record.getBodyHtml());
        patch.put("bodyText", record.getBodyText());
        patch.put("bodyMode", record.getBodyMode());
        // A MULTI_FILE column stores file ids; FileInfo is only its read shape.
        patch.put("attachments", record.getAttachments() == null ? null
                : record.getAttachments().stream().map(FileInfo::getFileId).toList());
        patch.put("priority", record.getPriority());
        patch.put("replyTo", record.getReplyTo());
        patch.put("readReceiptRequested", record.getReadReceiptRequested());
        patch.put("serverConfigId", record.getServerConfigId());
        patch.put("serverOwner", record.getServerOwner());
        patch.put("fromAddress", record.getFromAddress());
        return updateVersioned(patch);
    }

    @Override
    public void markSent(Long id, long expectedVersion,
                         String messageId, String providerName, int sendDurationMs) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", MailSendStatus.SENT);
        patch.put("sentAt", LocalDateTime.now());
        patch.put("sendDurationMs", sendDurationMs);
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
