package io.softa.starter.flow.service.impl;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.dto.FlowInboxCountView;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.query.TaskInstanceContextEnricher;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Tests for {@link FlowApprovalTaskServiceImpl#countInbox} — the badge counts
 * must use the exact filter definitions of the paged pending / unread-CC
 * queries (shared factories in ApprovalTaskQuerySupport).
 */
class FlowApprovalTaskCountTest {

    private FlowApprovalTaskServiceImpl serviceSpy() {
        return spy(new FlowApprovalTaskServiceImpl(
                mock(FlowApprovalTaskProjector.class),
                mock(io.softa.starter.flow.service.support.FlowInstanceAccessGuard.class),
                new ApprovalAuditReader(new NoopApprovalActionLedger()),
                new TaskInstanceContextEnricher(mock(FlowInstanceService.class),
                        mock(io.softa.starter.flow.service.support.FlowUserNames.class),
                        mock(io.softa.starter.flow.runtime.bundle.FlowBundleRegistry.class)),
                mock(FlowInstanceService.class)));
    }

    @Test
    void countsUseThePendingAndUnreadCcFilterDefinitions() {
        FlowApprovalTaskServiceImpl service = serviceSpy();
        doAnswer(invocation -> {
            String filters = invocation.getArgument(0, Filters.class).toString();
            if (filters.contains("Approval")) return 3L;
            if (filters.contains("Cc")) return 2L;
            return -1L;
        }).when(service).count(any(Filters.class));

        FlowInboxCountView counts = service.countInbox("user-1");
        assertEquals(3L, counts.pendingApprovals());
        assertEquals(2L, counts.unreadCc());
    }

    @Test
    void blankActorIsRejected() {
        FlowApprovalTaskServiceImpl service = serviceSpy();
        assertThrows(IllegalArgumentException.class, () -> service.countInbox(" "));
    }
}
