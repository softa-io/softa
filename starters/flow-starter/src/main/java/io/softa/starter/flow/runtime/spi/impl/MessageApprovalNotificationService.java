package io.softa.starter.flow.runtime.spi.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.Language;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.spi.impl.ApprovalNotices.Notice;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;

/**
 * The default {@link ApprovalNotificationService}: tells the people an approval concerns — who, and what, is
 * {@link ApprovalNotices}' — through message-starter, in the inbox always and by email on a flow that sends it.
 *
 * <ul>
 *   <li>Inbox: {@link NotificationType#WORKFLOW} messages that point at the flow instance ({@code sourceModel} =
 *       {@code FlowInstance}, {@code sourceId} = its instance id) — the client opens the instance and goes on from
 *       there. A task to act on and a CC are told here too, though My Tasks lists them: the inbox is where a newcomer
 *       to the approval hears of it. Texts are translated into the tenant's default language — a message is read
 *       later by people whose own language the action does not know.</li>
 *   <li>Email: the notices that name a mail template, to each recipient's account email, on a flow whose
 *       {@code emailNotification} setting is not turned off. The tenant's template of that code words the email; its
 *       variables are {@code subject} (what the approval is about), {@code content} (the comment or reason, when there
 *       is one), {@code flowName}, {@code recordCode} and {@code link} — the approval's page, {@code system.site-url}
 *       + {@code /home/approvals/{instanceId}} (the frontend's Home module). A recipient without an email is
 *       skipped.</li>
 * </ul>
 *
 * <p>Recipients are approver ids, i.e. login account ids. What each channel sends is worked out as the action runs and
 * written once its transaction commits, each channel in a transaction of its own: an action that rolls back sends
 * nothing, and a channel that cannot be told is logged without touching the action or keeping the other quiet. A
 * failure is told at once: it is recorded in a transaction of its own while the failed attempt's rolls back.
 */
@Slf4j
public class MessageApprovalNotificationService implements ApprovalNotificationService {

    private static final String INSTANCE_MODEL = FlowInstance.class.getSimpleName();
    private static final String TENANT_MODEL = "TenantInfo";
    private static final String TENANT_LANGUAGE_FIELD = "defaultLanguage";
    private static final String USER_MODEL = "UserAccount";
    private static final String ID_FIELD = "id";
    private static final String EMAIL_FIELD = "email";
    private static final String APPROVAL_ROUTE = "/home/approvals/";

    /** The inbox's title column width; a longer instance title is cut to fit. */
    private static final int TITLE_MAX_LENGTH = 200;

    /** The most messages message-starter takes in one batch. */
    private static final int BATCH_SIZE = 500;

    private final MessageService messageService;
    private final ModelService<Long> modelService;
    private final TransactionTemplate newTransaction;
    private final FlowBundleRegistry bundleRegistry;
    private final ObjectProvider<SystemConfig> systemConfig;
    /** The users told when an approval fails, besides its initiator: those who fix flows or end approvals. */
    private final Supplier<List<String>> administrators;

    @SuppressWarnings("unchecked")
    public MessageApprovalNotificationService(MessageService messageService, ModelService<?> modelService,
                                              PlatformTransactionManager transactionManager,
                                              FlowBundleRegistry bundleRegistry,
                                              ObjectProvider<SystemConfig> systemConfig,
                                              Supplier<List<String>> administrators) {
        this.messageService = messageService;
        this.modelService = (ModelService<Long>) modelService;
        this.bundleRegistry = bundleRegistry;
        this.systemConfig = systemConfig;
        this.administrators = administrators;
        // A transaction of its own: after commit the committed transaction is still bound to the thread, and a write
        // joining it would never be committed.
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void notify(FlowNotificationEvent event) {
        FlowExecutionState state = event.state();
        if (state == null) {
            return;
        }
        try {
            Notice notice = ApprovalNotices.of(event, administrators);
            List<Long> recipients = notice == null ? List.of() : accountIds(notice.recipients());
            if (recipients.isEmpty()) {
                log.debug("Flow notification not sent: instance={} has no recipient", state.getInstanceId());
                return;
            }
            List<Runnable> writes = new ArrayList<>(2);
            channel("inbox", state, () -> writes.add(inbox(state, notice, recipients)));
            if (notice.mailTemplate() != null) {
                channel("email", state, () -> {
                    if (sendsEmail(state)) {
                        writes.add(email(state, notice, recipients));
                    }
                });
            }
            Context context = ContextHolder.cloneContext();
            Runnable send = () -> ContextHolder.runWith(context,
                    () -> ContextUtils.withoutPermissionChecks(() -> writes.forEach(Runnable::run)));
            // Not inside the action's transaction: a failed write there marks it rollback-only, and the action then
            // fails on commit, whatever caught the exception.
            if (!(event instanceof FlowNotificationEvent.FlowFailed)
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        send.run();
                    }
                });
            } else {
                send.run();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to send flow notification for instance {}: {}", state.getInstanceId(), e.getMessage(), e);
        }
    }

    /** The inbox messages the notice makes, as a write to run once the action commits. */
    private Runnable inbox(FlowExecutionState state, Notice notice, List<Long> recipients) {
        String title = fit(translate(tenantLanguage(), notice.title(), ApprovalNotices.subjectOf(state)), TITLE_MAX_LENGTH);
        String content = StringUtils.hasText(notice.content()) ? notice.content() : title;
        List<SendInboxDTO> messages = recipients.stream().map(recipient -> {
            SendInboxDTO message = new SendInboxDTO();
            message.setRecipientId(recipient);
            message.setTitle(title);
            message.setContent(content);
            message.setNotificationType(NotificationType.WORKFLOW);
            message.setSourceModel(INSTANCE_MODEL);
            message.setSourceId(state.getInstanceId());
            return message;
        }).toList();
        return () -> channel("inbox", state, () -> newTransaction.executeWithoutResult(status -> {
            for (int from = 0; from < messages.size(); from += BATCH_SIZE) {
                messageService.sendInboxBatch(messages.subList(from, Math.min(messages.size(), from + BATCH_SIZE)));
            }
        }));
    }

    /** The emails the notice makes, to the recipients' account emails, as a write to run once the action commits. */
    private Runnable email(FlowExecutionState state, Notice notice, List<Long> recipients) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("subject", ApprovalNotices.subjectOf(state));
        variables.put("content", notice.content() == null ? "" : notice.content());
        variables.put("flowName", state.getFlowName() == null ? "" : state.getFlowName());
        variables.put("recordCode", state.getRecordCode() == null ? "" : state.getRecordCode());
        variables.put("link", link(state.getInstanceId()));
        return () -> channel("email", state, () -> {
            if (!ModelManager.existModel(USER_MODEL)) {
                log.debug("Approval email not sent: instance={}, no user accounts", state.getInstanceId());
                return;
            }
            List<SendMailDTO> mails = modelService.getByIds(USER_MODEL, recipients, List.of(ID_FIELD, EMAIL_FIELD))
                    .stream()
                    .map(user -> Objects.toString(user.get(EMAIL_FIELD), null))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .map(email -> {
                        SendMailDTO mail = new SendMailDTO();
                        mail.setTo(List.of(email));
                        mail.setTemplateCode(notice.mailTemplate());
                        mail.setTemplateVariables(variables);
                        return mail;
                    })
                    .toList();
            if (!mails.isEmpty()) {
                newTransaction.executeWithoutResult(status -> messageService.sendMailBatch(mails));
            }
        });
    }

    /** Runs one channel's part: one that cannot be told is logged, without touching the action or the other channel. */
    private static void channel(String channel, FlowExecutionState state, Runnable part) {
        try {
            part.run();
        } catch (RuntimeException e) {
            log.warn("Failed to send flow {} notification for instance {}: {}", channel, state.getInstanceId(),
                    e.getMessage(), e);
        }
    }

    /** Whether the instance's flow sends email: on unless its revision turned it off. */
    private boolean sendsEmail(FlowExecutionState state) {
        if (state.getBundleId() == null) {
            return true;
        }
        return bundleRegistry.getByBundleId(state.getBundleId())
                .map(definition -> !Boolean.FALSE.equals(definition.getEmailNotification()))
                .orElse(true);
    }

    /** The approval's page; empty when the site's address is not configured — the email still says what happened. */
    private String link(String instanceId) {
        SystemConfig config = systemConfig.getIfAvailable();
        if (config == null) {
            return "";
        }
        try {
            return config.resolveSiteUrl() + APPROVAL_ROUTE + instanceId;
        } catch (IllegalStateException e) {
            log.warn("Approval email for instance {} has no link: {}", instanceId, e.getMessage());
            return "";
        }
    }

    /** The tenant's default language, else the current context's, else the system default. */
    private String tenantLanguage() {
        Long tenantId = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        if (tenantId != null && ModelManager.existModel(TENANT_MODEL)) {
            Object code = ContextUtils.withoutPermissionChecks(() ->
                            modelService.getById(TENANT_MODEL, tenantId, List.of(TENANT_LANGUAGE_FIELD)))
                    .map(tenant -> tenant.get(TENANT_LANGUAGE_FIELD))
                    .orElse(null);
            if (code != null && Language.exists(code.toString())) {
                return code.toString();
            }
        }
        return ContextHolder.existContext() && ContextHolder.getContext().getLanguage() != null
                ? ContextHolder.getContext().getLanguage().getCode()
                : BaseConstant.DEFAULT_LANGUAGE.getCode();
    }

    private static String translate(String language, String text, Object... args) {
        Context context = ContextHolder.cloneContext();
        context.setLanguage(Language.of(language));
        return ContextHolder.callWith(context, () -> I18n.get(text, args));
    }

    /** {@code text}, cut to {@code maxLength} characters with an ellipsis when longer. */
    private static String fit(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
    }

    /** The recipients as login account ids, each once; one that is not an id is nobody to tell. */
    private static List<Long> accountIds(List<String> recipients) {
        if (recipients == null) {
            return List.of();
        }
        return recipients.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(MessageApprovalNotificationService::toLong)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static Long toLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
