package io.softa.starter.billing.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.billing.entity.PaymentRecord;
import io.softa.starter.billing.service.PaymentRecordService;

/**
 * PaymentRecord Model Service Implementation
 */
@Service
public class PaymentRecordServiceImpl extends EntityServiceImpl<PaymentRecord, String> implements PaymentRecordService {

}