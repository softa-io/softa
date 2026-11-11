package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.entity.TenantMailSendRecord;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.TenantMailSendRecordService;
import io.softa.starter.message.shared.dto.SendRecordIdsDTO;

/**
 * A tenant's send log: the reads come from the generic model routes over the
 * {@link TenantMailSendRecord} projection, narrowed to the tenant by the ORM, with no bodies or
 * template variables. The manual retries here requeue the underlying send record, and only one filed
 * under the calling tenant.
 */
@Tag(name = "TenantMailSendRecord")
@RestController
@RequestMapping("/TenantMailSendRecord")
public class TenantMailSendRecordController
        extends EntityController<TenantMailSendRecordService, TenantMailSendRecord, Long> {

    @Autowired
    private MailSendRecordService sendRecordService;

    /**
     * Requeue one of the tenant's records for delivery. Accepts PENDING / RETRY / FAILED / DEAD_LETTER;
     * rejects SENT and in-flight SENDING. Another tenant's record answers like a missing one.
     */
    @Operation(summary = "Manually requeue one of the tenant's mail records for delivery")
    @PostMapping("/retry")
    public ApiResponse<Boolean> retry(@RequestParam Long id) {
        return ApiResponse.success(sendRecordService.retryVisible(id));
    }

    /** Requeue the selected records of the tenant, skipping SENT, SENDING and other tenants' ones. */
    @Operation(summary = "Manually requeue the selected mail records of the tenant for delivery")
    @PostMapping("/retryByIds")
    public ApiResponse<Integer> retryByIds(@RequestBody @Valid SendRecordIdsDTO request) {
        return ApiResponse.success(sendRecordService.retryVisibleByIds(request.getIds()));
    }
}
