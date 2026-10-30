package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteValidationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A published flow is never deleted, whichever API the delete comes through; a draft that never published is.
 */
class PublishedFlowDeleteGuardTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final PublishedFlowDeleteGuard guard = new PublishedFlowDeleteGuard(modelService);

    @Test
    void guardsFlowDesignsOnly() {
        assertTrue(guard.supports("FlowDesign"));
        assertFalse(guard.supports("FlowBundle"));
    }

    @Test
    void aPublishedFlowIsNotDeleted() {
        when(modelService.count(eq("FlowDesign"), any(Filters.class))).thenReturn(1L);

        WriteValidationException refused = assertThrows(WriteValidationException.class,
                () -> asUser(() -> guard.validateDelete("FlowDesign", List.of("flow-1"))));

        assertEquals(PublishedFlowDeleteGuard.PUBLISHED, refused.getMessage());
    }

    @Test
    void aDraftThatNeverPublishedIsDeleted() {
        when(modelService.count(eq("FlowDesign"), any(Filters.class))).thenReturn(0L);

        assertDoesNotThrow(() -> asUser(() -> guard.validateDelete("FlowDesign", List.of("flow-1"))));
    }

    @Test
    void aSystemWriteIsTrusted() {
        asUser(() -> ContextUtils.withoutPermissionChecks(() -> guard.validateDelete("FlowDesign", List.of("flow-1"))));

        verifyNoInteractions(modelService);
    }

    private static void asUser(Runnable write) {
        Context user = new Context();
        user.setUserId(1001L);
        ContextHolder.runWith(user, write);
    }
}
