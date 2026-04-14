package io.softa.starter.message.sms.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.service.SmsSendRecordService;

/**
 * Implementation of {@link SmsSendRecordService}.
 */
@Service
public class SmsSendRecordServiceImpl extends EntityServiceImpl<SmsSendRecord, Long> implements SmsSendRecordService {

}
