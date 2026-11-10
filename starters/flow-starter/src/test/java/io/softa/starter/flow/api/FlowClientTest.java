package io.softa.starter.flow.api;

import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.service.FlowLaunchService;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The port makes the same service calls as {@code FlowInstanceController}, as the current user: the approval
 * preview of a record not saved yet is theirs.
 */
class FlowClientTest {

    private static final String ACTOR = "100";

    private final FlowLaunchService launch = mock(FlowLaunchService.class);

    private final FlowClient client = new FlowClient(launch);

    @Test
    void theApprovalPreviewOfUnsavedValuesIsTheCurrentUsers() {
        FlowApprovalPreview preview = FlowApprovalPreview.builder().status(FlowApprovalPreview.Status.NO_FLOW).build();
        Map<String, Object> values = Map.of("employeeId", 5000L);
        when(launch.previewValues("PayRunApproval", values, ACTOR)).thenReturn(preview);

        assertSame(preview, asCurrentUser(() -> client.previewValues("PayRunApproval", values)));
    }

    private static <T> T asCurrentUser(Supplier<T> call) {
        Context ctx = new Context();
        ctx.setUserId(Long.valueOf(ACTOR));
        return ContextHolder.callWith(ctx, call::get);
    }
}
