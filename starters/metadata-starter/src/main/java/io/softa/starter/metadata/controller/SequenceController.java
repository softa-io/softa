package io.softa.starter.metadata.controller;

import java.util.List;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.sequence.SequencePreview;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.entity.SysSequence;
import io.softa.starter.metadata.enums.SequenceMode;
import io.softa.starter.metadata.service.SysSequenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sequence-specific actions that do not fit the generic CRUD shape.
 * Exposes {@code next}, {@code nextBatch}, and {@code peek} over HTTP for
 * admin/runtime use cases.
 *
 * <p>NO_GAP sequences are rejected here: the no-gap guarantee requires the
 * counter UPDATE to share the business transaction, and an HTTP allocation
 * commits when the request returns — the caller's follow-up write can still
 * fail, losing the number. Allocating over HTTP would silently downgrade
 * NO_GAP to ALLOW_GAP semantics; call {@code SequenceService.next} inside the
 * business transaction instead. ALLOW_GAP sequences (which commit in their own
 * inner transaction by design) are served normally.
 *
 * <p>Counter reset is intentionally not exposed via REST in this version.
 * If needed, add a separate authenticated maintenance API with audit trail.
 */
@Tag(name = "Sequence")
@RestController
@RequestMapping("/Sequence")
@RequiredArgsConstructor
public class SequenceController {

    private static final int MAX_BATCH_COUNT = 100;

    private final SequenceService sequenceService;
    private final SysSequenceService sysSequenceService;

    @Operation(summary = "next", description = """
            Allocate and return the next sequence value for the given code.
            This operation consumes one number. ALLOW_GAP sequences only —
            a NO_GAP sequence must be allocated inside the business
            transaction (SequenceService.next), which HTTP cannot join.
            """)
    @PostMapping("/next")
    public ApiResponse<String> next(@RequestParam("code") String code) {
        Assert.notBlank(code, "code must not be blank");
        rejectNoGapOverHttp(code);
        return ApiResponse.success(sequenceService.next(code));
    }

    @Operation(summary = "nextBatch", description = """
            Allocate and return a batch of sequence values for the given code.
            This operation consumes `count` numbers. ALLOW_GAP sequences only —
            a NO_GAP sequence must be allocated inside the business
            transaction (SequenceService.nextBatch), which HTTP cannot join.
            """)
    @PostMapping("/nextBatch")
    public ApiResponse<List<String>> nextBatch(@RequestParam("code") String code,
                                               @RequestParam("count") Integer count) {
        Assert.notBlank(code, "code must not be blank");
        Assert.notNull(count, "count must not be null");
        Assert.isTrue(count > 0, "count must be greater than 0");
        Assert.isTrue(count <= MAX_BATCH_COUNT, "count must be less than or equal to " + MAX_BATCH_COUNT);
        rejectNoGapOverHttp(code);
        return ApiResponse.success(sequenceService.nextBatch(code, count));
    }

    @Operation(summary = "peek", description = """
            Preview the next sequence value for the given code. Read-only,
            no number is consumed; the actual value handed to next() may
            differ if another caller allocates first.
            """)
    @PostMapping("/peek")
    public ApiResponse<SequencePreview> peek(@RequestParam("code") String code) {
        Assert.notBlank(code, "code must not be blank");
        return ApiResponse.success(sequenceService.peek(code));
    }

    /**
     * An HTTP allocation commits when the request returns, so a NO_GAP counter
     * would advance even when the caller's business write later fails — the
     * number is lost and the no-gap promise silently broken. Fail loudly with
     * guidance instead. (A missing row surfaces as SequenceNotFoundException
     * from the config lookup, same as the allocation path.)
     */
    private void rejectNoGapOverHttp(String code) {
        SysSequence config = sysSequenceService.loadConfigByCode(code);
        Assert.notTrue(SequenceMode.NO_GAP == config.getMode(),
                "Sequence {0} is NO_GAP: allocate it inside the business transaction via "
                        + "SequenceService, not over HTTP (the no-gap guarantee cannot survive "
                        + "an HTTP boundary).", code);
    }
}
