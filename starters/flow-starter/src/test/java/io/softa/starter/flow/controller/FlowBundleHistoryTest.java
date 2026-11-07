package io.softa.starter.flow.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleMapper;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.impl.FlowDesignServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A flow's revision history is read from its stored revisions, which alone say which one is effective and what each
 * changed; restoring or activating a revision the caller got wrong is refused (400), not failed.
 */
class FlowBundleHistoryTest {

    private static final LocalDateTime FIRST_PUBLISHED = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime SECOND_PUBLISHED = LocalDateTime.of(2026, 10, 20, 9, 0);

    private final FlowBundleService bundleService = mock(FlowBundleService.class);
    private final FlowPublishService publishService = mock(FlowPublishService.class);
    private final FlowBundleController controller =
            new FlowBundleController(mock(FlowBundleRegistry.class), bundleService, publishService);
    private final CompiledFlowDefinition compiled = new DefaultFlowCompiler().compile(design());

    @Test
    void theHistorySaysWhichRevisionIsEffectiveAndWhatEachChanged() {
        FlowBundle second = revision(12L, 2, SECOND_PUBLISHED, true, "Added an HR step");
        FlowBundle first = revision(11L, 1, FIRST_PUBLISHED, false, null);
        when(bundleService.listRevisionsByFlowId("leave")).thenReturn(List.of(second, first));

        List<FlowBundleSummaryView> history = controller.list("leave").getData();

        assertEquals(List.of(12L, 11L), history.stream().map(FlowBundleSummaryView::getBundleId).toList());
        assertEquals(List.of(2, 1), history.stream().map(FlowBundleSummaryView::getRevision).toList());
        assertEquals(List.of(SECOND_PUBLISHED, FIRST_PUBLISHED),
                history.stream().map(FlowBundleSummaryView::getPublishedAt).toList());
        assertEquals(List.of(true, false), history.stream().map(FlowBundleSummaryView::getActive).toList());
        assertEquals("Added an HR step", history.getFirst().getChangeDescription());
        assertNull(history.getLast().getChangeDescription());
        assertEquals("Leave", history.getFirst().getFlowName());
        assertEquals(3, history.getFirst().getNodeCount(), "the compiled summary is kept");
    }

    @Test
    void anActivatedRevisionComesBackAsTheEffectiveOne() {
        when(publishService.activateBundle(11L)).thenReturn(Optional.of(compiled));
        when(bundleService.findById(11L)).thenReturn(Optional.of(revision(11L, 1, FIRST_PUBLISHED, true, null)));

        FlowBundleSummaryView activated = controller.activate(11L).getData();

        assertEquals(Boolean.TRUE, activated.getActive());
        assertEquals(1, activated.getRevision());
    }

    @Test
    void restoringTheWrongRevisionIsRefused() {
        FlowDesignServiceImpl designs = new FlowDesignServiceImpl();
        ReflectionTestUtils.setField(designs, "bundleService", bundleService);
        FlowBundle otherFlows = revision(21L, 1, FIRST_PUBLISHED, true, null);
        otherFlows.setFlowId("claim");
        otherFlows.setDesignJson(design());
        FlowBundle withoutSnapshot = revision(22L, 1, FIRST_PUBLISHED, true, null);
        when(bundleService.findById(20L)).thenReturn(Optional.empty());
        when(bundleService.findById(21L)).thenReturn(Optional.of(otherFlows));
        when(bundleService.findById(22L)).thenReturn(Optional.of(withoutSnapshot));

        assertThrows(FlowActionValidationException.class, () -> designs.restoreFromBundle("leave", 20L));
        assertThrows(FlowActionValidationException.class, () -> designs.restoreFromBundle("leave", 21L));
        assertThrows(FlowActionValidationException.class, () -> designs.restoreFromBundle("leave", 22L));
    }

    private FlowBundle revision(Long id, int revision, LocalDateTime publishedAt, boolean active, String change) {
        FlowBundle bundle = FlowBundleMapper.toEntity(compiled.toBuilder().revision(revision).build(), null, null, "leave");
        bundle.setId(id);
        bundle.setPublishedAt(publishedAt);
        bundle.setActive(active);
        bundle.setChangeDescription(change);
        return bundle;
    }

    private static DesignFlowDefinition design() {
        return DesignFlowDefinition.builder()
                .name("Leave")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                                FlowGraphNode.builder().id("manager").type(FlowNodeType.APPROVAL).label("Manager")
                                        .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of("2001"))))).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("manager").build(),
                                FlowGraphEdge.builder().id("e2").source("manager").target("end").build()))
                        .build())
                .build();
    }
}
