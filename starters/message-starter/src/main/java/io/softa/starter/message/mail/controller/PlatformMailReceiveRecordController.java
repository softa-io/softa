package io.softa.starter.message.mail.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.entity.PlatformMailReceiveRecord;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.softa.starter.message.mail.service.PlatformMailReceiveRecordService;

/**
 * REST controller for the mail the platform's receive servers fetched — bounces and receipts of the
 * mail its send servers carried among it. Platform menus only.
 */
@Tag(name = "PlatformMailReceiveRecord")
@RestController
@RequestMapping("/PlatformMailReceiveRecord")
public class PlatformMailReceiveRecordController
        extends EntityController<PlatformMailReceiveRecordService, PlatformMailReceiveRecord, Long> {

    @Autowired
    private MailReceiveService mailReceiveService;

    @Operation(summary = "Fetch new emails from the platform's default receive server")
    @PostMapping("/fetch")
    public ApiResponse<Integer> fetch() {
        return ApiResponse.success(mailReceiveService.fetchPlatformMails());
    }

    @Operation(summary = "Fetch new emails from one of the platform's receive servers")
    @PostMapping("/fetchByServer")
    public ApiResponse<Integer> fetchByServer(@RequestParam Long serverConfigId) {
        return ApiResponse.success(mailReceiveService.fetchPlatformMails(serverConfigId));
    }

    @Operation(summary = "Mark as read")
    @PostMapping("/markAsRead")
    public ApiResponse<Void> markAsRead(@RequestParam Long id) {
        mailReceiveService.markPlatformAsRead(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Mark multiple as read")
    @PostMapping("/markAsReadBatch")
    public ApiResponse<Void> markAsReadBatch(@RequestParam List<Long> ids) {
        mailReceiveService.markPlatformAsRead(ids);
        return ApiResponse.success();
    }
}
