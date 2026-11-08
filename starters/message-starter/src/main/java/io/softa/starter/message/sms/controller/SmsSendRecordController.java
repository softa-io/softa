package io.softa.starter.message.sms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.shared.dto.SendRecordIdsDTO;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.service.SmsSendRecordService;

/**
 * REST controller for SMS send record CRUD (audit log) plus the manual
 * {@code retry} / {@code retryByIds} operations.
 */
@Tag(name = "SmsSendRecord")
@RestController
@RequestMapping("/SmsSendRecord")
public class SmsSendRecordController
        extends EntityController<SmsSendRecordService, SmsSendRecord, Long> {

    /**
     * Manually requeue a stalled or failed record for delivery. Accepts
     * PENDING / RETRY / FAILED / DEAD_LETTER; rejects SENT and in-flight SENDING.
     * Safe to call repeatedly — the delivery claim is CAS-guarded.
     */
    @Operation(summary = "Manually requeue one SMS record for delivery")
    @PostMapping("/retry")
    public ApiResponse<Boolean> retry(@RequestParam Long id) {
        return ApiResponse.success(service.retry(id));
    }

    /**
     * Requeue the selected records, skipping SENT and in-flight SENDING ones
     * instead of failing. Returns how many were requeued.
     */
    @Operation(summary = "Manually requeue the selected SMS records for delivery")
    @PostMapping("/retryByIds")
    public ApiResponse<Integer> retryByIds(@RequestBody @Valid SendRecordIdsDTO request) {
        return ApiResponse.success(service.retryByIds(request.getIds()));
    }
}
