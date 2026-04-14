package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailSendRecord;

import java.util.Optional;

/**
 * CRUD service for outgoing mail records.
 */
public interface MailSendRecordService extends EntityService<MailSendRecord, Long> {

    /**
     * Find a send record by its SMTP Message-ID header value.
     * Used for correlating bounce/receipt emails back to the original sent message.
     *
     * @param messageId the SMTP Message-ID header value
     * @return the matching record, or empty if not found
     */
    Optional<MailSendRecord> findByMessageId(String messageId);
}
