package io.softa.starter.flow.runtime.engine;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The port's record reads make the same service calls as {@code FlowInstanceController}: a record's approval preview
 * is the current user's.
 */
class InProcessFlowClientInstanceReadsTest {

    private static final String ACTOR = "100";

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowLaunchService launch = mock(FlowLaunchService.class);
    private final FlowInstanceService instances = mock(FlowInstanceService.class);
    private final FlowInstanceAccessGuard accessGuard = mock(FlowInstanceAccessGuard.class);

    private final FlowClient client = new InProcessFlowClient(engine, launch, instances, accessGuard);

    @Test
    void theApprovalPreviewOfARecordIsTheCurrentUsers() {
        FlowApprovalPreview preview = FlowApprovalPreview.builder().status(FlowApprovalPreview.Status.NO_FLOW).build();
        when(launch.previewRecord("LeaveRequest", "42", ACTOR)).thenReturn(preview);

        assertSame(preview, asCurrentUser(() -> client.previewRecord("LeaveRequest", "42")));
    }

    private static <T> T asCurrentUser(Supplier<T> call) {
        Context ctx = new Context();
        ctx.setUserId(Long.valueOf(ACTOR));
        return ContextHolder.callWith(ctx, call::get);
    }
}
