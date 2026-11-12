package io.softa.starter.flow.runtime.engine;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.vo.OptionReference;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The approval lock: a leave request ({@code LeaveRequest}, approved through a handler) and its days
 * ({@code LeaveDay}, whose {@code leaveRequestId} cascades) are frozen while the request is in approval
 * and once it is approved, and the request's approval fields are the framework's. A holiday that only
 * points at a request ({@code Holiday}, no cascade) is not the request's to freeze.
 */
class ApprovalLockValidatorTest {

    private static final String REQUEST = "LeaveRequest";
    private static final String DAY = "LeaveDay";
    private static final String HOLIDAY = "Holiday";

    @SuppressWarnings("unchecked")
    private final ModelApprovalHandler<LeaveRequest> handler = mock(ModelApprovalHandler.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private ApprovalLockValidator validator;
    /** The requests' stored approval statuses, by id. */
    private final Map<Long, String> requests = new HashMap<>();
    private MockedStatic<ModelManager> modelManager;
    private MockedStatic<IdUtils> idUtils;

    @BeforeEach
    void setUp() {
        when(handler.modelClass()).thenReturn(LeaveRequest.class);
        validator = new ApprovalLockValidator(ModelApprovalHandlerRegistry.of(List.of(handler)), modelService);
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.getModelFields(REQUEST)).thenReturn(List.of());
        modelManager.when(() -> ModelManager.getModelFields(DAY)).thenReturn(List.of(link("leaveRequestId", OnDelete.CASCADE)));
        modelManager.when(() -> ModelManager.getModelFields(HOLIDAY)).thenReturn(List.of(link("leaveRequestId", null)));
        idUtils = mockStatic(IdUtils.class);
        idUtils.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> Long.valueOf(invocation.getArgument(1).toString()));
        when(modelService.searchList(eq(REQUEST), any(FlexQuery.class))).thenAnswer(invocation -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            requests.forEach((id, status) -> rows.add(Map.of("id", id, "approvalStatus", status)));
            return rows;
        });
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
        idUtils.close();
    }

    @Test
    void appliesToTheApprovedModelAndTheModelsItOwns() {
        assertTrue(validator.supports(REQUEST));
        assertTrue(validator.supports(DAY));
        assertFalse(validator.supports(HOLIDAY));
    }

    @Test
    void onlyADraftCanBeChanged() {
        Map<String, String> frozen = Map.of("Pending", "in approval", "Approved", "approved",
                "Rejected", "rejected", "Cancelled", "cancelled");
        frozen.forEach((status, reason) -> {
            WriteValidationException refused = assertThrows(WriteValidationException.class,
                    () -> asUser(() -> validator.validateUpdate(update(REQUEST, Map.of("days", 2), stored(status)))));
            assertTrue(refused.getMessage().contains(reason), refused.getMessage());
        });
        WriteContext draft = update(REQUEST, Map.of("days", 2), stored("Draft"));
        asUser(() -> validator.validateUpdate(draft));
        assertFalse(draft.hasErrors());
    }

    @Test
    void nobodyEditsTheApprovalFieldsButSendingThemBackIsNoEdit() {
        Map<String, Object> stored = stored("Draft");
        stored.put("submitterId", 1001L);
        stored.put("submittedTime", LocalDateTime.of(2026, 10, 28, 9, 30, 0, 123_000_000));

        WriteContext edit = update(REQUEST, Map.of("approvalStatus", "Approved", "submitterId", 1002L), stored);
        asUser(() -> validator.validateUpdate(edit));
        // A form that puts the whole record back, in the shapes the API reads it out.
        OptionReference draft = new OptionReference();
        ReflectionTestUtils.setField(draft, "itemCode", "Draft");
        WriteContext echo = update(REQUEST, Map.of("approvalStatus", draft, "submitterId", "1001",
                "submittedTime", "2026-10-28 09:30:00"), stored);
        asUser(() -> validator.validateUpdate(echo));

        assertEquals(List.of("approvalStatus", "submitterId"),
                edit.errors().stream().map(WriteValidationException.FieldError::field).toList());
        assertFalse(echo.hasErrors(), () -> echo.errors().toString());
    }

    @Test
    void aNewRequestIsADraftNeverSubmitted() {
        WriteContext submitted = WriteContext.of(REQUEST, AccessType.CREATE,
                Map.of("approvalStatus", "Pending", "submittedTime", "2026-10-28 09:30:00"), null);
        WriteContext draft = WriteContext.of(REQUEST, AccessType.CREATE, Map.of("approvalStatus", "Draft", "days", 1), null);

        asUser(() -> validator.validateCreate(submitted));
        asUser(() -> validator.validateCreate(draft));

        assertEquals(List.of("approvalStatus", "submittedTime"),
                submitted.errors().stream().map(WriteValidationException.FieldError::field).toList());
        assertFalse(draft.hasErrors());
    }

    @Test
    void aDayIsNotWrittenUnderARequestInApprovalOrApproved() {
        requests.put(42L, "Pending");
        requests.put(43L, "Draft");
        requests.put(44L, "Approved");

        assertThrows(WriteValidationException.class, () -> asUser(() -> validator.validateCreate(
                WriteContext.of(DAY, AccessType.CREATE, Map.of("leaveRequestId", 42L), null))));
        // Moving a day from an editable request to an approved one.
        assertThrows(WriteValidationException.class, () -> asUser(() -> validator.validateUpdate(
                update(DAY, Map.of("leaveRequestId", Map.of("id", 44L)), new HashMap<>(Map.of("id", 7L, "leaveRequestId", 43L))))));
        WriteContext editable = update(DAY, Map.of("hours", 4), new HashMap<>(Map.of("id", 7L, "leaveRequestId", 43L)));
        asUser(() -> validator.validateUpdate(editable));

        assertFalse(editable.hasErrors());
    }

    @Test
    void aRequestInApprovalAndItsDaysAreNotDeleted() {
        requests.put(42L, "Pending");
        when(modelService.searchList(eq(DAY), any(FlexQuery.class))).thenReturn(List.of(Map.of("id", 7L, "leaveRequestId", 42L)));

        assertThrows(WriteValidationException.class, () -> asUser(() -> validator.validateDelete(REQUEST, List.of(42L))));
        WriteValidationException day = assertThrows(WriteValidationException.class,
                () -> asUser(() -> validator.validateDelete(DAY, List.of(7L))));

        assertTrue(day.getMessage().contains("belongs to"), day.getMessage());
        // Withdrawn: a draft again, and its days with it.
        requests.put(42L, "Draft");
        asUser(() -> validator.validateDelete(REQUEST, List.of(42L)));
        asUser(() -> validator.validateDelete(DAY, List.of(7L)));
    }

    @Test
    void anApprovedRequestAndItsDaysAreNotDeleted() {
        requests.put(42L, "Approved");
        when(modelService.searchList(eq(DAY), any(FlexQuery.class))).thenReturn(List.of(Map.of("id", 7L, "leaveRequestId", 42L)));

        WriteValidationException request = assertThrows(WriteValidationException.class,
                () -> asUser(() -> validator.validateDelete(REQUEST, List.of(42L))));
        WriteValidationException day = assertThrows(WriteValidationException.class,
                () -> asUser(() -> validator.validateDelete(DAY, List.of(7L))));

        assertTrue(request.getMessage().contains("approved"), request.getMessage());
        assertTrue(day.getMessage().contains("approved"), day.getMessage());
    }

    @Test
    void aRejectedOrCancelledRequestAndItsDaysAreNotDeleted() {
        when(modelService.searchList(eq(DAY), any(FlexQuery.class))).thenReturn(List.of(Map.of("id", 7L, "leaveRequestId", 42L)));
        for (String status : List.of("Rejected", "Cancelled")) {
            requests.put(42L, status);
            WriteValidationException request = assertThrows(WriteValidationException.class,
                    () -> asUser(() -> validator.validateDelete(REQUEST, List.of(42L))));
            WriteValidationException day = assertThrows(WriteValidationException.class,
                    () -> asUser(() -> validator.validateDelete(DAY, List.of(7L))));
            assertTrue(request.getMessage().contains(status.toLowerCase()), request.getMessage());
            assertTrue(day.getMessage().contains(status.toLowerCase()), day.getMessage());
        }
    }

    @Test
    void theSystemsOwnWritesAreExempt() {
        requests.put(42L, "Pending");
        WriteContext approverEdit = update(REQUEST, Map.of("days", 2, "approvalStatus", "Approved"), stored("Pending"));

        asUser(() -> ContextUtils.withoutPermissionChecks(() -> {
            validator.validateUpdate(approverEdit);
            validator.validateDelete(REQUEST, List.of(42L));
        }));

        assertFalse(approverEdit.hasErrors());
    }

    private static void asUser(Runnable write) {
        Context user = new Context();
        user.setUserId(1001L);
        ContextHolder.runWith(user, write);
    }

    private static WriteContext update(String model, Map<String, Object> patch, Map<String, Object> stored) {
        Map<String, Object> merged = new HashMap<>(stored);
        merged.putAll(patch);
        return WriteContext.of(model, AccessType.UPDATE, merged, patch, stored);
    }

    private static Map<String, Object> stored(String approvalStatus) {
        return new HashMap<>(Map.of("id", 42L, "days", 1, "approvalStatus", approvalStatus));
    }

    private static MetaField link(String fieldName, OnDelete onDelete) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(field, "relatedModel", REQUEST);
        ReflectionTestUtils.setField(field, "onDelete", onDelete);
        return field;
    }
}
