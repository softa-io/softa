package io.softa.starter.flow.event;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.seed.event.SeedFileLoadedEvent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The flow designs a seed file creates or rewrites are published; other rows are not flows, and a
 * design that does not compile fails the file.
 */
class SeedFlowPublisherTest {

    private final FlowPublishService publishService = mock(FlowPublishService.class);
    private final SeedFlowPublisher publisher = new SeedFlowPublisher(publishService);

    @Test
    void theFlowDesignsAFileCreatedArePublished() {
        publisher.onSeedFileLoaded(new SeedFileLoadedEvent(7L, "FlowDesign.ExpenseClaim.json",
                Map.of("FlowDesign", List.of("f1", "f2"), "SysSequence", List.of("9")), Map.of()));

        verify(publishService).publishDraft("f1", "Default flow");
        verify(publishService).publishDraft("f2", "Default flow");
        verifyNoMoreInteractions(publishService);
    }

    @Test
    void aDesignAReapplyRewroteIsPublishedAgain() {
        // Left a draft, the template would sit in the designer while the tenant's instances ran the old version.
        publisher.onSeedFileLoaded(new SeedFileLoadedEvent(7L, "FlowDesign.ExpenseClaim.json",
                Map.of(), Map.of("FlowDesign", List.of("f1"), "SysSequence", List.of("9"))));

        verify(publishService).publishDraft("f1", "Default flow");
        verifyNoMoreInteractions(publishService);
    }

    @Test
    void aDesignThatDoesNotCompileFailsTheFile() {
        when(publishService.publishDraft(anyString(), anyString())).thenThrow(new FlowRuntimeException("bad graph"));

        assertThrows(FlowRuntimeException.class, () -> publisher.onSeedFileLoaded(new SeedFileLoadedEvent(7L,
                "FlowDesign.ExpenseClaim.json", Map.of("FlowDesign", List.of("f1")), Map.of())));
    }
}
