package io.softa.starter.message.sms.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for SMS send record CRUD (audit log).
 */
@Tag(name = "SmsSendRecord")
@RestController
@RequestMapping("/SmsSendRecord")
public class SmsSendRecordController
        extends EntityController<SmsSendRecordService, SmsSendRecord, Long> {
}
