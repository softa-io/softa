package io.softa.starter.metadata.sequence.controller;

import java.util.List;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.sequence.SequencePreview;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sequence-specific actions that do not fit the generic CRUD shape.
 * Exposes {@code next}, {@code nextBatch}, and {@code peek} over HTTP for
 * admin/runtime use cases.
 *
 * <p>{@code next} and {@code nextBatch} run within a transaction boundary
 * to satisfy NO_GAP mode requirements.
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

    @Operation(summary = "next", description = """
            Allocate and return the next sequence value for the given code.
            This operation consumes one number.
            """)
    @PostMapping("/next")
    @Transactional
    public ApiResponse<String> next(@RequestParam("code") String code) {
        Assert.notBlank(code, "code must not be blank");
        return ApiResponse.success(sequenceService.next(code));
    }

    @Operation(summary = "nextBatch", description = """
            Allocate and return a batch of sequence values for the given code.
            This operation consumes `count` numbers.
            """)
    @PostMapping("/nextBatch")
    @Transactional
    public ApiResponse<List<String>> nextBatch(@RequestParam("code") String code,
                                               @RequestParam("count") Integer count) {
        Assert.notBlank(code, "code must not be blank");
        Assert.notNull(count, "count must not be null");
        Assert.isTrue(count > 0, "count must be greater than 0");
        Assert.isTrue(count <= MAX_BATCH_COUNT, "count must be less than or equal to " + MAX_BATCH_COUNT);
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
}
