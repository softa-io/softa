package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailReceiveRecord;

/** CRUD service for the mail a tenant's own receive servers fetched. Multi-tenant. */
public interface MailReceiveRecordService extends EntityService<MailReceiveRecord, Long> {
}
