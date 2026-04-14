package io.softa.starter.message.mail.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for incoming mail records.
 * Inherits standard CRUD endpoints from EntityController.
 */
@Tag(name = "MailReceiveRecord")
@RestController
@RequestMapping("/MailReceiveRecord")
public class MailReceiveRecordController
        extends EntityController<MailReceiveRecordService, MailReceiveRecord, Long> {

    @Autowired
    private MailReceiveService mailReceiveService;

    @Operation(summary = "Fetch new emails",
            description = "Actively poll the resolved IMAP/POP3 server and persist any new messages.")
    @PostMapping("/fetch")
    public ApiResponse<Integer> fetch() {
        int count = mailReceiveService.fetchNewMails();
        return ApiResponse.success(count);
    }

    @Operation(summary = "Fetch from specific server",
            description = "Poll a specific mail server config and persist any new messages.")
    @PostMapping("/fetchByServer")
    public ApiResponse<Integer> fetchByServer(@RequestParam Long serverConfigId) {
        int count = mailReceiveService.fetchNewMails(serverConfigId);
        return ApiResponse.success(count);
    }

    @Operation(summary = "Mark as read")
    @PostMapping("/markAsRead")
    public ApiResponse<Void> markAsRead(@RequestParam Long id) {
        mailReceiveService.markAsRead(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Mark multiple as read")
    @PostMapping("/markAsReadBatch")
    public ApiResponse<Void> markAsReadBatch(@RequestParam List<Long> ids) {
        mailReceiveService.markAsRead(ids);
        return ApiResponse.success();
    }
}
