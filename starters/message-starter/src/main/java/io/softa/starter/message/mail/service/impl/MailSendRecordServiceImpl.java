package io.softa.starter.message.mail.service.impl;

import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.service.MailSendRecordService;

/**
 * MailSendRecord service implementation.
 */
@Service
public class MailSendRecordServiceImpl extends EntityServiceImpl<MailSendRecord, Long>
        implements MailSendRecordService {

    @Override
    public Optional<MailSendRecord> findByMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return Optional.empty();
        Filters filters = new Filters().eq(MailSendRecord::getMessageId, messageId);
        return this.searchOne(filters);
    }
}
