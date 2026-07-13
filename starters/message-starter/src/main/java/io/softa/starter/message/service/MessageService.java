package io.softa.starter.message.service;

import java.util.List;

import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.sms.dto.SendSmsDTO;

/**
 * The single application-facing facade for submitting messages.
 * <p>
 * Mail and SMS are persisted as PENDING records with transactional outbox rows
 * and delivered asynchronously. Inbox notifications are persisted immediately.
 * Every method joins the caller's transaction; a surrounding rollback cancels
 * the whole submission. Batch methods are atomic and preserve input order in
 * their returned record IDs.
 */
public interface MessageService {

    /** Submit one email message. */
    Long sendMail(SendMailDTO message);

    /** Submit 1..500 independent email messages atomically. */
    List<Long> sendMailBatch(List<SendMailDTO> messages);

    /** Submit one SMS message for one phone number. */
    Long sendSms(SendSmsDTO message);

    /** Submit 1..500 independent SMS messages atomically. */
    List<Long> sendSmsBatch(List<SendSmsDTO> messages);

    /** Persist one inbox notification for one recipient. */
    Long sendInbox(SendInboxDTO message);

    /** Persist 1..500 independent inbox notifications atomically. */
    List<Long> sendInboxBatch(List<SendInboxDTO> messages);
}
