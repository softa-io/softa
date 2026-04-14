package io.softa.starter.message.mail.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for outgoing mail records (read-only audit log).
 * Records are created automatically by MailSendService and must not be created via API.
 */
@Tag(name = "MailSendRecord")
@RestController
@RequestMapping("/MailSendRecord")
public class MailSendRecordController
        extends EntityController<MailSendRecordService, MailSendRecord, Long> {
}
