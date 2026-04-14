package io.softa.starter.message.mail.service.impl;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import org.springframework.stereotype.Service;

/**
 * MailReceiveRecord service implementation.
 */
@Service
public class MailReceiveRecordServiceImpl extends EntityServiceImpl<MailReceiveRecord, Long>
        implements MailReceiveRecordService {
}
