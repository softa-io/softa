package io.softa.starter.flow.service.impl;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.TestActorAccounts;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.FlowDelegationService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A bulk reassignment — and its preview — to someone who cannot act is refused as a whole, naming them, before any
 * task is read or moved.
 */
class FlowAdministrationReassignTest {

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final ModelService<?> modelService = mock(ModelService.class);
    private final FlowAdministrationServiceImpl service = new FlowAdministrationServiceImpl(engine,
            mock(FlowInstanceAccessGuard.class), mock(FlowDelegationService.class), modelService);

    @Test
    void aNewApproverWhoCannotActIsRefused() {
        // Bob's account is frozen.
        ReflectionTestUtils.setField(service, "actorAccounts",
                TestActorAccounts.of(Map.of(2002L, "Frozen"), Map.of("2002", "Bob Wu")));

        FlowActionValidationException preview = assertThrows(FlowActionValidationException.class,
                () -> service.previewReassign("2001", "2002"));
        FlowActionValidationException reassign = assertThrows(FlowActionValidationException.class,
                () -> service.reassign("2001", "2002", "Ann is away", "admin-1"));

        assertEquals("Bob Wu's account is frozen.", preview.getMessage());
        assertEquals("Bob Wu's account is frozen.", reassign.getMessage());
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
        verifyNoInteractions(engine);
    }
}
