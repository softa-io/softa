package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.PlatformMailReceiveRecord;

/** CRUD service for the mail the platform's own receive servers fetched. */
public interface PlatformMailReceiveRecordService extends EntityService<PlatformMailReceiveRecord, Long> {
}
