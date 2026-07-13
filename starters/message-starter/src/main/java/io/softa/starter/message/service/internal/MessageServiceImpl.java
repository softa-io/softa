package io.softa.starter.message.service.internal;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;

/** Public facade implementation; channel details remain in package-private handlers. */
@Service
@Transactional
public class MessageServiceImpl implements MessageService {

    private static final int MAX_BATCH_SIZE = 500;

    private final MailMessageHandler mailHandler;
    private final SmsMessageHandler smsHandler;
    private final InboxMessageHandler inboxHandler;

    MessageServiceImpl(MailMessageHandler mailHandler,
                       SmsMessageHandler smsHandler,
                       InboxMessageHandler inboxHandler) {
        this.mailHandler = mailHandler;
        this.smsHandler = smsHandler;
        this.inboxHandler = inboxHandler;
    }

    @Override
    public Long sendMail(SendMailDTO message) {
        return mailHandler.send(requireMessage("mail", message));
    }

    @Override
    public List<Long> sendMailBatch(List<SendMailDTO> messages) {
        List<SendMailDTO> batch = requireBatch("mail", messages);
        List<Long> ids = new ArrayList<>(batch.size());
        batch.forEach(message -> ids.add(mailHandler.send(message)));
        return List.copyOf(ids);
    }

    @Override
    public Long sendSms(SendSmsDTO message) {
        return smsHandler.send(requireMessage("sms", message));
    }

    @Override
    public List<Long> sendSmsBatch(List<SendSmsDTO> messages) {
        List<SendSmsDTO> batch = requireBatch("sms", messages);
        List<Long> ids = new ArrayList<>(batch.size());
        batch.forEach(message -> ids.add(smsHandler.send(message)));
        return List.copyOf(ids);
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
