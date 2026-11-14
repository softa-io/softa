package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.TenantMailSendRecord;
import io.softa.starter.message.mail.service.TenantMailSendRecordService;

/** Implementation of {@link TenantMailSendRecordService}. */
@Service
public class TenantMailSendRecordServiceImpl extends EntityServiceImpl<TenantMailSendRecord, Long>
        implements TenantMailSendRecordService {
}
