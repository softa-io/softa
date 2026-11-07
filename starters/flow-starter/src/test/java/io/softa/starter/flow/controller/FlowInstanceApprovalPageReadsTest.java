package io.softa.starter.flow.controller;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.aspect.DataMaskAspect;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.jdbc.pipeline.processor.MaskingProcessor;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowBundleDetailView;
import io.softa.starter.flow.dto.FlowInstanceRecordView;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleMapper;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.impl.FlowInstanceServiceImpl;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.service.query.FlowInstanceSummaryComposer;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard.RecordAccess;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard.RecordAdmission;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reads an approval page makes through its instance, for people who may hold neither the menu of the
 * record's model nor any permission on the flow models: the record it approves — past their own permissions
 * for the initiator and the task holders, through them for a reader of the record, masked either way, and the
 * same read keyed by the record, through whichever approval the guard names — the instance's own row, composed
 * as the caller's own search composes it, and the revision it runs, as the bundle endpoint returns it. The
 * access guard rules on each before anything is read.
 */
class FlowInstanceApprovalPageReadsTest {

    private static final String MODEL = "ExpenseClaim";
    private static final String INITIATOR = "1001";
    private static final String APPROVER = "2001";
    private static final String READER = "3001";
    private static final String OUTSIDER = "9999";

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowInstanceService instances = mock(FlowInstanceService.class);
    private final FlowInstanceAccessGuard accessGuard = mock(FlowInstanceAccessGuard.class);
    private final FlowBundleService bundles = mock(FlowBundleService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowUserNames names = mock(FlowUserNames.class);
    private final FlowInstanceStore instanceStore = mock(FlowInstanceStore.class);
    private final WithdrawPolicyGuard withdrawPolicyGuard = mock(WithdrawPolicyGuard.class);
    private final UrgeThrottle urgeThrottle = mock(UrgeThrottle.class);
    private final FlowInstanceSummaryComposer composer = new FlowInstanceSummaryComposer(modelService, names,
            instanceStore, withdrawPolicyGuard, urgeThrottle, ModelApprovalHandlerRegistry.of(List.of()));

    // ---- the record ----

    @Test
    void theInitiatorAndTheTaskHoldersGetTheRecordAsTheStepsShowIt() {
        FlowExecutionState state = approval();
        FlowInstanceRecordView view = new FlowInstanceRecordView(Map.of("id", 7L), Map.of());
        when(accessGuard.requireRecordAccess(state, APPROVER)).thenReturn(RecordAccess.GRANTED);
        when(instances.recordOf(state, APPROVER)).thenReturn(Optional.of(view));

        assertSame(view, as(APPROVER, () -> controller(instances).getInstanceRecord("i1")).getData());
        verify(instances, never()).recordThroughOwnPermissions(any(), any());
    }

    @Test
    void aReaderOfTheRecordGetsItThroughTheirOwnPermissions() {
        FlowExecutionState state = approval();
        FlowInstanceRecordView view = new FlowInstanceRecordView(Map.of("id", 7L), Map.of());
        when(accessGuard.requireRecordAccess(state, READER)).thenReturn(RecordAccess.OWN_PERMISSIONS);
        when(instances.recordThroughOwnPermissions(MODEL, "7")).thenReturn(Optional.of(view));

        assertSame(view, as(READER, () -> controller(instances).getInstanceRecord("i1")).getData());
        verify(instances, never()).recordOf(any(), any());
    }

    @Test
    void readByTheRecordItIsLentByTheApprovalTheGuardNamesOrReadWithTheCallersOwnPermissions() {
        // A page keyed by the record: the guard names the approval that lends it, whichever approval is current.
        FlowExecutionState earlier = FlowExecutionState.builder().instanceId("i0").modelName(MODEL).rowId("7")
                .recordApproval(true).status(FlowExecutionStatus.RETURNED).initiatorId(INITIATOR).build();
        FlowInstanceRecordView lent = new FlowInstanceRecordView(Map.of("id", 7L), Map.of());
        FlowInstanceRecordView own = new FlowInstanceRecordView(Map.of("id", 7L, "note", "lunch"), Map.of());
        when(accessGuard.requireRecordAccess(MODEL, "7", APPROVER)).thenReturn(new RecordAdmission(RecordAccess.GRANTED, earlier));
        when(accessGuard.requireRecordAccess(MODEL, "7", READER)).thenReturn(new RecordAdmission(RecordAccess.OWN_PERMISSIONS, null));
        when(instances.recordOf(earlier, APPROVER)).thenReturn(Optional.of(lent));
        when(instances.recordThroughOwnPermissions(MODEL, "7")).thenReturn(Optional.of(own));
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
            FlowInstanceController controller = controller(instances);

            assertSame(lent, as(APPROVER, () -> controller.getRecordThroughApprovals(MODEL, "7")).getData());
            assertSame(own, as(READER, () -> controller.getRecordThroughApprovals(MODEL, "7")).getData());
            // A model there is not: not found before anyone is asked anything.
            assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                    as(OUTSIDER, () -> controller.getRecordThroughApprovals("NoSuchModel", "7")).getCode());
            verify(accessGuard, never()).requireRecordAccess(eq("NoSuchModel"), any(), any());
            // Never through an instance: the engine is not asked for one.
            verifyNoInteractions(engine);
        }
    }

    @Test
    void anyoneElseIsRefusedTheRecord() {
        FlowExecutionState state = approval();
        when(accessGuard.requireRecordAccess(state, OUTSIDER)).thenThrow(new FlowAuthorizationException("no"));

        assertThrows(FlowAuthorizationException.class,
                () -> as(OUTSIDER, () -> controller(instances).getInstanceRecord("i1")));
        verifyNoInteractions(instances);
    }

    @Test
    void aMissingInstanceOrRecordIsNotFound() {
        FlowExecutionState state = approval();
        when(accessGuard.requireRecordAccess(state, APPROVER)).thenReturn(RecordAccess.GRANTED);
        when(instances.recordOf(state, APPROVER)).thenReturn(Optional.empty());
        when(engine.getInstance("gone")).thenReturn(Optional.empty());
        FlowInstanceController controller = controller(instances);

        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(APPROVER, () -> controller.getInstanceRecord("i1")).getCode());
        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(APPROVER, () -> controller.getInstanceRecord("gone")).getCode());
        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(APPROVER, () -> controller.getInstanceSummary("gone")).getCode());
        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(APPROVER, () -> controller.getInstanceDesign("gone")).getCode());
    }

    @Test
    void maskedFieldsAreMaskedInsideTheRecordAsOnEveryModelRead() {
        // @DataMask walks no response: it turns masking on in the caller's context while the endpoint runs, and the
        // read pipeline masks a masked field as it reads the row. What the row is then wrapped in changes nothing.
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
            models.when(() -> ModelManager.getModelStoredFields(MODEL)).thenReturn(List.of("id", "phone"));
            ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            FlowInstanceServiceImpl service = new FlowInstanceServiceImpl();
            ReflectionTestUtils.setField(service, "bundleRegistry", new StubFlowBundleRegistry());
            ReflectionTestUtils.setField(service, "modelService", modelService);
            MaskingProcessor masking = new MaskingProcessor(phone(), AccessType.READ);
            when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation -> {
                // What the read pipeline does with every row it reads.
                List<Map<String, Object>> rows = List.of(new LinkedHashMap<>(Map.of("id", 7L, "phone", "13812345678")));
                masking.batchProcessOutputRows(rows);
                return Optional.of(rows.getFirst());
            });
            FlowExecutionState state = approval();
            state.setBundleId(1L);
            when(accessGuard.requireRecordAccess(state, APPROVER)).thenReturn(RecordAccess.GRANTED);
            when(accessGuard.requireRecordAccess(state, READER)).thenReturn(RecordAccess.OWN_PERMISSIONS);
            when(accessGuard.requireRecordAccess(MODEL, "7", APPROVER)).thenReturn(new RecordAdmission(RecordAccess.GRANTED, state));
            when(accessGuard.requireRecordAccess(MODEL, "7", READER)).thenReturn(new RecordAdmission(RecordAccess.OWN_PERMISSIONS, null));
            FlowInstanceController controller = controller(service);
            FlowInstanceController endpoint = asServed(controller);
            String masked = MaskingProcessor.maskingPhoneNumber("13812345678");

            // A grantee's read runs on a copy of their context with the permission checks set aside — the masking
            // goes with the copy — and a reader's read in their own.
            assertEquals(masked, as(APPROVER, () -> endpoint.getInstanceRecord("i1")).getData().record().get("phone"));
            assertEquals(masked, as(READER, () -> endpoint.getInstanceRecord("i1")).getData().record().get("phone"));
            // Read by the record, the same: whichever approval lends it, or none.
            assertEquals(masked,
                    as(APPROVER, () -> endpoint.getRecordThroughApprovals(MODEL, "7")).getData().record().get("phone"));
            assertEquals(masked,
                    as(READER, () -> endpoint.getRecordThroughApprovals(MODEL, "7")).getData().record().get("phone"));
            // Without the annotation's switch, the same reads mask nothing.
            assertEquals("13812345678",
                    as(APPROVER, () -> controller.getInstanceRecord("i1")).getData().record().get("phone"));
            assertEquals("13812345678",
                    as(READER, () -> controller.getRecordThroughApprovals(MODEL, "7")).getData().record().get("phone"));
        }
    }

    // ---- the instance's summary ----

    @Test
    void theSummaryIsTheRowTheCallersOwnSearchShows() {
        FlowExecutionState state = approval();
        FlowInstance row = new FlowInstance();
        row.setInstanceId("i1");
        row.setFlowName("Claims");
        row.setStatus(FlowExecutionStatus.WAITING);
        row.setRecordApproval(true);
        row.setInitiatorId(INITIATOR);
        row.setModelName(MODEL);
        row.setRowId("7");
        row.setSubjectId(42L);
        row.setDepartmentId(5L);
        row.setEffectiveDate(LocalDate.of(2026, 11, 1));
        row.setRecordCode("EC-0007");
        List<Boolean> skipped = new ArrayList<>();
        when(instances.findByInstanceIds(List.of("i1"))).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return List.of(row);
        });
        Page<FlowInstance> page = Page.of(1, 50);
        page.setRows(List.of(row));
        when(instances.searchSummaries(any(), eq(INITIATOR))).thenReturn(page);
        when(modelService.searchList(eq("FlowApprovalTask"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("instanceId", "i1", "nodeId", "approval", "nodeLabel", "Manager", "actorId", APPROVER)));
        when(instanceStore.listByInstanceIds(any())).thenReturn(List.of(state));
        when(withdrawPolicyGuard.withdrawable(state, INITIATOR)).thenReturn(true);
        FlowUrgeStatus urge = new FlowUrgeStatus(true, null);
        when(urgeThrottle.status(eq(state), eq(INITIATOR), any())).thenReturn(urge);
        FlowInstanceController controller = controller(instances);

        FlowInstanceSummaryView summary = as(INITIATOR, () -> controller.getInstanceSummary("i1")).getData();
        FlowInstanceSummaryView searched = as(INITIATOR,
                () -> controller.searchInstances(FlowInstanceSearchRequest.of(null, null))).getData().getRows().getFirst();

        assertEquals(searched, summary);
        assertEquals("EC-0007", summary.getRecordCode());
        assertEquals(List.of(new FlowRecordApprovalsView.Actor(APPROVER, null)),
                summary.getCurrentSteps().getFirst().getApprovers());
        assertTrue(summary.getWithdrawable());
        assertSame(urge, summary.getUrge());
        // Ruled on first, then read past the row scope the guard ruled on, as the caller's own search reads its rows.
        verify(accessGuard).requireInstanceViewer(state, INITIATOR);
        assertEquals(List.of(true), skipped);
    }

    // ---- the revision it runs ----

    @Test
    void theDesignIsTheRevisionTheInstanceRunsAsTheBundleEndpointReturnsIt() {
        DesignFlowDefinition design = claims();
        CompiledFlowDefinition compiled = new FlowPublishServiceImpl(new DefaultFlowCompiler(),
                new StubFlowBundleRegistry()).publish("claims", design);
        FlowBundle bundle = FlowBundleMapper.toEntity(compiled, design, null, "claims");
        bundle.setId(compiled.getBundleId());
        List<Boolean> skipped = new ArrayList<>();
        when(bundles.findById(bundle.getId())).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return Optional.of(bundle);
        });
        FlowExecutionState state = approval();
        state.setBundleId(bundle.getId());

        FlowBundleDetailView ofInstance = as(APPROVER, () -> controller(instances).getInstanceDesign("i1")).getData();
        FlowBundleDetailView ofBundle = as(APPROVER,
                () -> new FlowBundleController(null, bundles, null).get(bundle.getId(), "design")).getData();

        assertNotNull(ofInstance.design());
        assertEquals(JsonUtils.objectToString(ofBundle), JsonUtils.objectToString(ofInstance));
        // The instance's viewers read the revision past their permissions on FlowBundle; the bundle endpoint does not.
        verify(accessGuard).requireInstanceViewer(state, APPROVER);
        assertEquals(List.of(true, false), skipped);
    }

    @Test
    void theSummaryAndTheDesignAreRefusedToWhoeverMayNotViewTheInstance() {
        FlowExecutionState state = approval();
        doThrow(new FlowAuthorizationException("no")).when(accessGuard).requireInstanceViewer(state, OUTSIDER);
        FlowInstanceController controller = controller(instances);

        assertThrows(FlowAuthorizationException.class, () -> as(OUTSIDER, () -> controller.getInstanceSummary("i1")));
        assertThrows(FlowAuthorizationException.class, () -> as(OUTSIDER, () -> controller.getInstanceDesign("i1")));
        verifyNoInteractions(instances, bundles);
    }

    // ---- helpers ----

    private FlowInstanceController controller(FlowInstanceService instanceService) {
        return new FlowInstanceController(engine, null, instanceService, null, accessGuard, null, urgeThrottle,
                withdrawPolicyGuard, null, composer, names, bundles);
    }

    /** A record's approval waiting on its approver, as the engine loads it. */
    private FlowExecutionState approval() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1").modelName(MODEL).rowId("7").recordApproval(true)
                .status(FlowExecutionStatus.WAITING).initiatorId(INITIATOR)
                .build();
        when(engine.getInstance("i1")).thenReturn(Optional.of(state));
        return state;
    }

    /** The controller as Spring serves it: behind the aspect {@code @DataMask} declares. */
    private static FlowInstanceController asServed(FlowInstanceController controller) {
        AspectJProxyFactory proxy = new AspectJProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new DataMaskAspect());
        return proxy.getProxy();
    }

    private static MetaField phone() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MODEL);
        when(field.getFieldName()).thenReturn("phone");
        when(field.getMaskingType()).thenReturn(MaskingType.PHONE_NUMBER);
        return field;
    }

    private static <T> T as(String userId, Supplier<T> call) {
        Context context = new Context();
        context.setUserId(Long.valueOf(userId));
        return ContextHolder.callWith(context, call::get);
    }

    private static DesignFlowDefinition claims() {
        return DesignFlowDefinition.builder()
                .name("Claims")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger(MODEL))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                                        .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of(APPROVER))))).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
