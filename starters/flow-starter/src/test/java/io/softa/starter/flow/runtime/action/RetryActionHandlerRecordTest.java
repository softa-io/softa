package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.api.FlowRetryRequest;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.FlowExecutionOrchestrator;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A record's approval runs again only while the record still waits on it (zingkey-hcm#2385): not once its submitter
 * took it back — a draft again — nor once it was submitted again, its new approval the one it waits on. Otherwise
 * the steps after the approval would run on a record that moved on.
 */
class RetryActionHandlerRecordTest {

    private static final String MODEL = "ExpenseClaim";
    private static final String ROW = "42";

    private final FlowActionContextService contextService = mock(FlowActionContextService.class);
    private final FlowExecutionOrchestrator orchestrator = mock(FlowExecutionOrchestrator.class);
    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final RetryActionHandler handler = new RetryActionHandler(contextService, mock(FlowAuditService.class),
            orchestrator, instanceService, modelService);

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        MetaField id = mock(MetaField.class);
        when(id.getFieldType()).thenReturn(FieldType.LONG);
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.getModelField(MODEL, "id")).thenReturn(id);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aRecordTakenBackByItsSubmitterIsNotRunAgain() {
        failedApproval("failed");
        approvals(instance("failed", LocalDateTime.of(2026, 11, 2, 9, 0)));
        record("Draft");

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> handler.handle(retry("failed")));

        assertThat(refused.getMessage()).contains("took it back");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void aRecordSubmittedAgainWaitsOnItsNewApprovalNotThisOne() {
        failedApproval("failed");
        approvals(instance("failed", LocalDateTime.of(2026, 11, 2, 9, 0)),
                instance("again", LocalDateTime.of(2026, 11, 2, 10, 0)));
        record("Pending");

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> handler.handle(retry("failed")));

        assertThat(refused.getMessage()).contains("submitted again");
        verifyNoInteractions(orchestrator);
    }

    private void failedApproval(String instanceId) {
        when(contextService.requireState(instanceId)).thenReturn(FlowExecutionState.builder()
                .instanceId(instanceId)
                .bundleId(7L)
                .modelName(MODEL)
                .rowId(ROW)
                .recordApproval(true)
                .status(FlowExecutionStatus.FAILED)
                .failedNodeId("hook")
                .retryAfterNodeId("approval")
                .trace(new ArrayList<>())
                .approvalAuditDelta(new ArrayList<>())
                .build());
    }

    private void approvals(FlowInstance... instances) {
        when(instanceService.findByModelNameAndRowId(MODEL, ROW)).thenReturn(List.of(instances));
    }

    private void record(String approvalStatus) {
        when(modelService.getById(eq(MODEL), any(), anyCollection()))
                .thenReturn(Optional.of(Map.of("approvalStatus", approvalStatus)));
    }

    private static FlowInstance instance(String instanceId, LocalDateTime createdTime) {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId(instanceId);
        instance.setRecordApproval(true);
        instance.setCreatedTime(createdTime);
        return instance;
    }

    private static FlowRetryRequest retry(String instanceId) {
        FlowRetryRequest request = new FlowRetryRequest();
        request.setInstanceId(instanceId);
        request.setActorId("hr-admin");
        return request;
    }
}
