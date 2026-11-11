package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.TenantMailSendRecord;

/**
 * Read service for {@link TenantMailSendRecord}, a tenant's view of its own send log. Read-only: the
 * model is a projection, so the ORM rejects every write.
 */
public interface TenantMailSendRecordService extends EntityService<TenantMailSendRecord, Long> {
}
