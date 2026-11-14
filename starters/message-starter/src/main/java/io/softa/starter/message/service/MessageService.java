package io.softa.starter.message.service;

import java.util.List;

import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.sms.dto.SendSmsDTO;

/**
 * The single application-facing facade for submitting messages.
 * <p>
 * Mail and SMS are stored as PENDING send records and delivered asynchronously
 * by the delivery loop, which renders the template, chooses the server or
 * provider and charges the monthly quota on the first attempt — so a send that
 * cannot go out as configured still has its record, FAILED with the reason.
 * Acceptance throws only for what the caller got wrong (no recipient, a
 * malformed address or number, no content and no template), and does so
 * before writing anything. Inbox notifications are persisted immediately.
 * Every method joins the caller's transaction; a surrounding rollback cancels
 * the whole submission. Batch methods are atomic and preserve input order in
 * their returned record IDs.
 * <p>
 * A send record belongs to the caller's tenant, whatever the scope: the scope
 * picks the template and the servers, the tenant is whom the send is filed
 * under and whose monthly quota it draws on — a platform notice about a
 * tenant's account is that tenant's send. To send for another tenant — from a
 * scheduled job, or an anonymous link that names its tenant — make the call
 * inside {@code ContextUtils.inTenantContext(tenantId, …)}. A send from a
 * context with no tenant (a login code for someone in several companies) is
 * filed under the platform tier.
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
