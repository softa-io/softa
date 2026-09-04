package io.softa.starter.message;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Consumes {@link MailRequestMessage} off the mail-request MQ topic and delivers it through the mail
 * pipeline. Lets any starter request a <b>templated</b> mail without depending on message-starter (⊥):
 * the producer publishes to the topic, this consumer maps it to a {@link SendMailDTO} (template code +
 * variables + tier scope) and hands it to {@link MessageService}, which renders the
 * {@code MailTemplate} and delivers via its outbox pipeline. Registered only when
 * {@code mq.topics.mail-request.topic} is configured (Pulsar optional) — same gating as the other
 * message consumers.
 *
 * <p>The MQ hop drops the producer's thread context, so the message itself names the tenant: the
 * consumer restores {@code message.tenantId()} before accepting the send, so a {@code TENANT}-scoped
 * render reaches that tenant's own template and the send record lands in the tenant's books. A
 * {@code PLATFORM}-scoped message renders from the platform tier regardless of the restored context.
 */
@Slf4j
@Component
// Gated on the SUBSCRIPTION name, not the topic: with MailRequestPublisher living in this starter,
// every service that can publish mail configures the topic — but only the deployment that owns
// delivery (SMTP + templates) declares a subscription. Topic-only config = publish-only role.
@ConditionalOnProperty(name = "mq.topics.mail-request.sub")
public class MailRequestConsumer {

    private final MessageService messageService;

    public MailRequestConsumer(MessageService messageService) {
        this.messageService = messageService;
    }

    @PulsarListener(topics = "${mq.topics.mail-request.topic}",
            subscriptionName = "${mq.topics.mail-request.sub}")
    public void onMessage(MailRequestMessage message) {
        if (message == null || message.to() == null || message.to().isEmpty()
                || message.templateCode() == null || message.templateCode().isBlank()) {
            return;
        }
        SendMailDTO mail = new SendMailDTO();
        mail.setTo(message.to());
        mail.setTemplateCode(message.templateCode());
        mail.setTemplateVariables(message.variables());
        mail.setScope(message.scope());
        Context ctx = ContextHolder.cloneContext();
        if (message.tenantId() != null) {
            ctx.setTenantId(message.tenantId());
        } else if (message.scope() == MessageScope.PLATFORM) {
            // A platform-scoped request names no tenant, because none is known yet — a login or
            // forgot-password code is asked for before any session exists. Left as-is, the record
            // this send writes is stamped from an empty context: the ORM puts tenant_id = NULL
            // EXPLICITLY into the insert (AutofillFields), and the column is NOT NULL, so the row
            // either fails outright or is coerced to 0 by a lenient server. Both are wrong — the
            // first loses the mail, the second files it under a tenant that does not exist.
            //
            // The platform tier is the right home, and the rest of the pipeline already agrees:
            // template and server resolve explicitly at PLATFORM, and MonthlyQuotaGuard.bucketFor
            // charges the platform's own quota. Only the record was left out.
            //
            // Deliberately NOT applied to a null tenant on a TENANT-scoped message. That
            // combination is a producer bug (the sender had a tenant and failed to pass it), and
            // pinning it here would render the platform's copy under a tenant's name and hide the
            // mistake.
            ctx.setTenantId(TenantScopes.PLATFORM);
        }
        ContextHolder.runWith(ctx, () -> messageService.sendMail(mail));
        log.debug("Delivered mail-request → template '{}' to {} recipient(s), tenantId={}, scope={}",
                message.templateCode(), message.to().size(), message.tenantId(), message.scope());
    }
}
