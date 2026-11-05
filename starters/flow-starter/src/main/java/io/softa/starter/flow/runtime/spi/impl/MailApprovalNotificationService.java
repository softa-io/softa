package io.softa.starter.flow.runtime.spi.impl;

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
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.spi.impl.ApprovalNotices.Notice;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;

/**
 * The email channel of {@link ApprovalNotificationService}: the notices {@link ApprovalNotices} gives a mail
 * template, sent through message-starter to each recipient's account email, on a flow that sends email (its
 * {@code emailNotification} setting, on unless turned off). The tenant's template of that code words the email;
 * its variables are {@code subject} (what the approval is about), {@code content} (the comment or reason, when
 * there is one), {@code flowName}, {@code recordCode} and {@code link} — the approval's page,
 * {@code system.site-url} + {@code /home/approvals/{instanceId}} (the frontend's Home module), where the recipient acts
 * on it.
 *
 * <p>Like the inbox: written after the action commits, in a transaction of its own, a failure at once; a
 * recipient without an email is skipped, and an email that cannot be sent is logged without touching the action.
 */
@Slf4j
public class MailApprovalNotificationService implements ApprovalNotificationService {

    private static final String USER_MODEL = "UserAccount";
    private static final String ID_FIELD = "id";
    private static final String EMAIL_FIELD = "email";
    private static final String APPROVAL_ROUTE = "/home/approvals/";

    private final ObjectProvider<MessageService> messageService;
    private final ModelService<Long> modelService;
    private final TransactionTemplate newTransaction;
    private final FlowBundleRegistry bundleRegistry;
    private final ObjectProvider<SystemConfig> systemConfig;
    private final Supplier<List<String>> administrators;

    @SuppressWarnings("unchecked")
    public MailApprovalNotificationService(ObjectProvider<MessageService> messageService, ModelService<?> modelService,
                                           PlatformTransactionManager transactionManager, FlowBundleRegistry bundleRegistry,
                                           ObjectProvider<SystemConfig> systemConfig,
                                           Supplier<List<String>> administrators) {
        this.messageService = messageService;
        this.modelService = (ModelService<Long>) modelService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bundleRegistry = bundleRegistry;
        this.systemConfig = systemConfig;
        this.administrators = administrators;
    }

    @Override
    public void notify(FlowNotificationEvent event) {
        FlowExecutionState state = event.state();
        if (state == null || !sendsEmail(state)) {
            return;
        }
        Notice notice = ApprovalNotices.of(event, administrators);
        if (notice == null || notice.mailTemplate() == null || notice.recipients() == null) {
            return;
        }
        List<Long> recipients = notice.recipients().stream().map(MailApprovalNotificationService::toLong)
                .filter(Objects::nonNull).distinct().toList();
        if (recipients.isEmpty()) {
            return;
        }
        Map<String, Object> variables = variables(state, notice);
        Context context = ContextHolder.cloneContext();
        Runnable send = () -> ContextHolder.runWith(context, () -> ContextUtils.withoutPermissionChecks(
                () -> write(recipients, notice.mailTemplate(), variables, state.getInstanceId())));
        if (!(event instanceof FlowNotificationEvent.FlowFailed) && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
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

    private Map<String, Object> variables(FlowExecutionState state, Notice notice) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("subject", ApprovalNotices.subjectOf(state));
        variables.put("content", notice.content() == null ? "" : notice.content());
        variables.put("flowName", state.getFlowName() == null ? "" : state.getFlowName());
        variables.put("recordCode", state.getRecordCode() == null ? "" : state.getRecordCode());
        variables.put("link", link(state.getInstanceId()));
        return variables;
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

    private void write(List<Long> recipients, String templateCode, Map<String, Object> variables, String instanceId) {
        MessageService messages = messageService.getIfAvailable();
        if (messages == null || !ModelManager.existModel(USER_MODEL)) {
            log.debug("Approval email not sent: instance={}, no mail service or user accounts", instanceId);
            return;
        }
        try {
            List<SendMailDTO> mails = modelService.getByIds(USER_MODEL, recipients, List.of(ID_FIELD, EMAIL_FIELD)).stream()
                    .map(user -> Objects.toString(user.get(EMAIL_FIELD), null))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .map(email -> {
                        SendMailDTO mail = new SendMailDTO();
                        mail.setTo(List.of(email));
                        mail.setTemplateCode(templateCode);
                        mail.setTemplateVariables(variables);
                        return mail;
                    })
                    .toList();
            if (mails.isEmpty()) {
                return;
            }
            newTransaction.executeWithoutResult(status -> messages.sendMailBatch(mails));
        } catch (RuntimeException e) {
            log.warn("Failed to send approval email for instance {}: {}", instanceId, e.getMessage(), e);
        }
    }

    private static Long toLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
