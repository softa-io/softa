package io.softa.starter.message.service.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;

/**
 * Public facade implementation; channel details remain in package-private handlers.
 * <p>
 * Mail and SMS acceptance validate every message before writing any, and do not roll back on a
 * {@link BusinessException}: a rejected request leaves the caller's transaction usable, so a caller
 * that notifies as a side effect can log the rejection and carry on with its own change.
 */
@Service
@Transactional
public class MessageServiceImpl implements MessageService {

    private static final int MAX_BATCH_SIZE = 500;

    private final MailMessageHandler mailHandler;
    private final SmsMessageHandler smsHandler;
    private final InboxMessageHandler inboxHandler;
    private final MailServerDispatcher mailServerDispatcher;
    private final SmsProviderDispatcher smsProviderDispatcher;

    MessageServiceImpl(MailMessageHandler mailHandler,
                       SmsMessageHandler smsHandler,
                       InboxMessageHandler inboxHandler,
                       MailServerDispatcher mailServerDispatcher,
                       SmsProviderDispatcher smsProviderDispatcher) {
        this.mailHandler = mailHandler;
        this.smsHandler = smsHandler;
        this.inboxHandler = inboxHandler;
        this.mailServerDispatcher = mailServerDispatcher;
        this.smsProviderDispatcher = smsProviderDispatcher;
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public Long sendMail(SendMailDTO message) {
        return mailHandler.send(requireMessage("mail", message));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public List<Long> sendMailBatch(List<SendMailDTO> messages) {
        return mailHandler.sendBatch(requireBatch("mail", messages));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public Long sendSms(SendSmsDTO message) {
        return smsHandler.send(requireMessage("sms", message));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public List<Long> sendSmsBatch(List<SendSmsDTO> messages) {
        return smsHandler.sendBatch(requireBatch("sms", messages));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public void requireMailRoute(MessageScope scope) {
        mailServerDispatcher.resolveSend(scope == null ? MessageScope.TENANT : scope);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public void requireSmsRoute(String phoneNumber) {
        if (!StringUtils.hasText(phoneNumber)) {
            throw new BusinessException("SMS send rejected: phoneNumber is required");
        }
        smsProviderDispatcher.resolveProviders(phoneNumber);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public Optional<String> mailTemplateModel(String templateCode) {
        return mailHandler.modelOf(templateCode);
    }

    @Override
    public boolean canSendSms(String phoneNumber) {
        return smsHandler.canSend(phoneNumber);
    }

    @Override
    public Long sendInbox(SendInboxDTO message) {
        return inboxHandler.send(requireMessage("inbox", message));
    }

    @Override
    public List<Long> sendInboxBatch(List<SendInboxDTO> messages) {
        return List.copyOf(inboxHandler.sendBatch(requireBatch("inbox", messages)));
    }

    private static <T> T requireMessage(String channel, T message) {
        if (message == null) {
            throw new BusinessException("{0} message must not be null", channel);
        }
        return message;
    }

    private static <T> List<T> requireBatch(String channel, List<T> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException("{0} message batch must contain at least one item", channel);
        }
        if (messages.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("{0} message batch exceeds the limit of {1}",
                    channel, MAX_BATCH_SIZE);
        }
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == null) {
                throw new BusinessException("{0} message batch item {1} must not be null", channel, i);
            }
        }
        return messages;
    }
}
