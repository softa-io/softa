package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.PlatformMailReceiveRecord;
import io.softa.starter.message.mail.service.PlatformMailReceiveRecordService;

/** Implementation of {@link PlatformMailReceiveRecordService}. */
@Service
public class PlatformMailReceiveRecordServiceImpl extends EntityServiceImpl<PlatformMailReceiveRecord, Long>
        implements PlatformMailReceiveRecordService {
}
