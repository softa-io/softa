package io.softa.starter.message.service;

import java.util.List;
import java.util.Optional;

import io.softa.framework.base.message.MessageScope;

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

    /**
     * Refuse a mail of {@code scope} that could not go out as things are configured now — no send
     * server for the caller's tenant and none on the platform to relay it. Writes nothing.
     * <p>
     * For a caller that must tell its user straight away whether a message can leave: acceptance
     * does not check this, delivery does, and its answer is only on the record. Anything delivery
     * alone can learn (a rejected recipient, a bounce, a used-up quota) is not checked here.
     *
     * @throws io.softa.framework.base.exception.BusinessException naming what is missing
     */
    void requireMailRoute(MessageScope scope);

    /**
     * The SMS counterpart of {@link #requireMailRoute}: refuse a number that does not parse, or whose
     * country no enabled route serves. Writes nothing.
     *
     * @throws io.softa.framework.base.exception.BusinessException naming what is missing
     */
    void requireSmsRoute(String phoneNumber);

    /**
     * Whether an SMS can be sent to {@code phoneNumber} now: it is a number {@link #sendSms} accepts
     * (country code included, the right number of digits for its country), and an enabled route
     * serves its country. For a screen about to text a code to a number the person just typed, so it
     * can tell them before sending instead of sending into a country nobody routed. {@link #sendSms}
     * does not refuse such a number: routing is read at delivery, and the record fails there.
     */
    boolean canSendSms(String phoneNumber);

    /**
     * The model a mail template is bound to — the one whose record fills it — or empty when the
     * template takes its values from the sender, or does not exist. Looked up among the current
     * tenant's templates: only those can be bound to a model.
     *
     * <p>For a sender that has a record at hand and needs to know whether to send it
     * ({@link SendMailDTO#ofRecord}) or to send values of its own.
     *
     * @param templateCode a tenant mail template's code
     */
    Optional<String> mailTemplateModel(String templateCode);

    /** Persist one inbox notification for one recipient. */
    Long sendInbox(SendInboxDTO message);

    /** Persist 1..500 independent inbox notifications atomically. */
    List<Long> sendInboxBatch(List<SendInboxDTO> messages);
}
