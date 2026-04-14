package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailReceiveRecord;

/**
 * CRUD service for incoming mail records.
 */
public interface MailReceiveRecordService extends EntityService<MailReceiveRecord, Long> {
}
