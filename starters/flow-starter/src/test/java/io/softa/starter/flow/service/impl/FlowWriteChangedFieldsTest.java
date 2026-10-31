package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.service.FlowBundleService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * A design, delegation or bundle is read before it is changed. Each change writes only
 * what it sets, so a read that comes back with fields cut — null where the row has a value — never writes
 * those nulls to the database. The fixtures read back with everything but a name or owner cut.
 */
class FlowWriteChangedFieldsTest {

    private static final String FLOW = "flow-1";

    /** A published design read with every field but id and name cut. */
    private static FlowDesign cutDesign() {
        FlowDesign design = new FlowDesign();
        design.setId(FLOW);
        design.setFlowName("Leave approval");
        return design;
    }

    private static FlowDesignServiceImpl designService() {
        FlowDesignServiceImpl service = spy(new FlowDesignServiceImpl());
        doReturn(Optional.of(cutDesign())).when(service).getById(FLOW);
        doReturn(true).when(service).updateOne(any(FlowDesign.class));
        return service;
    }

    private static FlowDesign written(FlowDesignServiceImpl service) {
        ArgumentCaptor<FlowDesign> patch = ArgumentCaptor.forClass(FlowDesign.class);
        verify(service).updateOne(patch.capture());
        verify(service, never()).updateOne(any(FlowDesign.class), anyBoolean());
        return patch.getValue();
    }

    @Test
    void savingADraftWritesTheDocumentAndLeavesThePublishedRevisionAlone() {
        FlowDesignServiceImpl service = designService();
        DesignFlowDefinition document = DesignFlowDefinition.builder().build();

        service.saveDraft(FLOW, new FlowDesignSaveRequest(document, null, FlowScenario.APPROVAL, 3));

        FlowDesign patch = written(service);
        assertEquals(FLOW, patch.getId());
        assertEquals(3, patch.getVersion());
        assertEquals(FlowScenario.APPROVAL, patch.getScenario());
        assertSame(document, patch.getDesignJson());
        assertNull(patch.getFlowName(), "a name the request leaves out is not written");
        assertNull(patch.getPublishedRevision());
        assertNull(patch.getPublishedChecksum());
    }

    @Test
    void publishingWritesTheRevisionAndNeverTheDocument() {
        FlowDesignServiceImpl service = designService();

        service.upsertFromPublish(FLOW, 4);

        FlowDesign patch = written(service);
        assertEquals(4, patch.getPublishedRevision());
        assertNull(patch.getDesignJson(), "the document read as null must not be written back");
    }

    @Test
    void restoringABundleWritesOnlyItsDocument() {
        FlowDesignServiceImpl service = designService();
        FlowBundle bundle = new FlowBundle();
        bundle.setFlowId(FLOW);
        DesignFlowDefinition document = DesignFlowDefinition.builder().build();
        bundle.setDesignJson(document);
        FlowBundleService bundles = mock(FlowBundleService.class);
        when(bundles.findById(7L)).thenReturn(Optional.of(bundle));
        ReflectionTestUtils.setField(service, "bundleService", bundles);

        service.restoreFromBundle(FLOW, 7L);

        FlowDesign patch = written(service);
        assertSame(document, patch.getDesignJson());
        assertNull(patch.getPublishedRevision());
        assertNull(patch.getPublishedChecksum());
        assertNull(patch.getScenario());
    }

    /** A delegation read with every field but id and the owner the cancel check reads cut. */
    private static FlowDelegationServiceImpl delegationService() {
        FlowDelegation cut = new FlowDelegation();
        cut.setId(9L);
        cut.setDelegatorId("emp-1");
        FlowDelegationServiceImpl service = spy(new FlowDelegationServiceImpl());
        doReturn(Optional.of(cut)).when(service).getById(9L);
        doReturn(true).when(service).updateOne(any(FlowDelegation.class));
        return service;
    }

    private static FlowDelegation writtenDelegation(FlowDelegationServiceImpl service) {
        ArgumentCaptor<FlowDelegation> patch = ArgumentCaptor.forClass(FlowDelegation.class);
        verify(service).updateOne(patch.capture());
        verify(service, never()).updateOne(any(FlowDelegation.class), anyBoolean());
        assertEquals(9L, patch.getValue().getId());
        assertNull(patch.getValue().getDelegateId(), "the delegate read as null must not be written back");
        assertNull(patch.getValue().getStartTime());
        return patch.getValue();
    }

    @Test
    void cancellingADelegationWritesOnlyTheFlag() {
        FlowDelegationServiceImpl service = delegationService();

        service.cancelDelegation(9L, "emp-1");

        assertEquals(Boolean.FALSE, writtenDelegation(service).getActive());
    }

    @Test
    void recordingADelegatedTaskWritesOnlyTheUsage() {
        FlowDelegationServiceImpl service = delegationService();

        service.recordDelegationUsage(9L);

        FlowDelegation patch = writtenDelegation(service);
        assertEquals(1, patch.getDelegatedTaskCount());
        assertNotNull(patch.getLastDelegationTime());
        assertNull(patch.getActive());
    }

    @Test
    void activatingARevisionWritesOnlyTheFlags() {
        FlowBundle current = new FlowBundle();
        current.setId(1L);
        current.setFlowId(FLOW);
        current.setRevision(1);
        current.setActive(true);
        FlowBundle target = new FlowBundle();
        target.setId(2L);
        target.setFlowId(FLOW);
        target.setRevision(2);
        target.setActive(false);
        FlowBundleServiceImpl service = spy(new FlowBundleServiceImpl());
        doReturn(List.of(current)).when(service).searchList(any(Filters.class));
        doReturn(Optional.of(target)).when(service).searchOne(any(Filters.class));
        doReturn(true).when(service).updateOne(any(FlowBundle.class));

        service.markAsActive(FLOW, 2);

        ArgumentCaptor<FlowBundle> patch = ArgumentCaptor.forClass(FlowBundle.class);
        verify(service, times(2)).updateOne(patch.capture());
        verify(service, never()).updateOne(any(FlowBundle.class), anyBoolean());
        List<FlowBundle> patches = patch.getAllValues();
        assertEquals(1L, patches.get(0).getId());
        assertEquals(Boolean.FALSE, patches.get(0).getActive());
        assertEquals(2L, patches.get(1).getId());
        assertEquals(Boolean.TRUE, patches.get(1).getActive());
        patches.forEach(p -> {
            assertNull(p.getCompiledJson(), "the compiled definition must not be written back");
            assertNull(p.getRevision());
            assertNull(p.getFlowId());
        });
    }
}
