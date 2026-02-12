package io.softa.starter.billing.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.billing.entity.ServiceRecord;
import io.softa.starter.billing.service.ServiceRecordService;

/**
 * ServiceRecord Model Service Implementation
 */
@Service
public class ServiceRecordServiceImpl extends EntityServiceImpl<ServiceRecord, Long> implements ServiceRecordService {

}