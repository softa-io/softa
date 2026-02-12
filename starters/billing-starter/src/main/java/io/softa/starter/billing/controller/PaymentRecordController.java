package io.softa.starter.billing.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.billing.entity.PaymentRecord;
import io.softa.starter.billing.service.PaymentRecordService;

/**
 * PaymentRecord Model Controller
 */
@Tag(name = "PaymentRecord")
@RestController
@RequestMapping("/PaymentRecord")
public class PaymentRecordController extends EntityController<PaymentRecordService, PaymentRecord, Long> {

}