package io.softa.starter.flow.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.config.JacksonConfig;
import io.softa.framework.orm.domain.FilterEvaluator;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.support.StoredFieldsFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link FlowInstanceServiceImpl#searchSummaries} — the filter and
 * projection builder shared by the caller's "my instances" reads (REST and
 * {@code FlowClient}) and the cross-initiator monitor endpoint.
 */
class FlowInstanceServiceImplSearchTest {

    private final FlowInstanceServiceImpl service = spy(new FlowInstanceServiceImpl());

    private final ArgumentCaptor<FlexQuery> queryCaptor = ArgumentCaptor.forClass(FlexQuery.class);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<Page<FlowInstance>> pageCaptor =
            ArgumentCaptor.forClass((Class<Page<FlowInstance>>) (Class<?>) Page.class);

    private static FlowInstanceSearchRequest request(String initiatorId,
                                                     LocalDateTime createdFrom, LocalDateTime createdTo,
                                                     Integer pageNumber, Integer pageSize) {
        return new FlowInstanceSearchRequest(null, null, initiatorId, null, null,
                createdFrom, createdTo, null, null, null, null, null, null, null, null, pageNumber, pageSize);
    }

    private void runSearch(FlowInstanceSearchRequest request, String forcedInitiatorId) {
        doReturn(Page.<FlowInstance>of(1, 50)).when(service).searchPage(any(FlexQuery.class), any());
        service.searchSummaries(request, forcedInitiatorId);
        verify(service).searchPage(queryCaptor.capture(), pageCaptor.capture());
    }

    @Test
    void forcedInitiatorOverridesRequestedInitiator() {
        runSearch(request("someoneElse", null, null, null, null), "me");
        String filters = queryCaptor.getValue().getFilters().toString();
        assertTrue(filters.contains("\"me\"") || filters.contains("me"));
        assertFalse(filters.contains("someoneElse"));
    }

    @Test
    void monitorSearchHonorsRequestedInitiator() {
        runSearch(request("targetUser", null, null, null, null), null);
        assertTrue(queryCaptor.getValue().getFilters().toString().contains("targetUser"));
    }

    @Test
    void monitorSearchWithoutInitiatorAppliesNoInitiatorFilter() {
        runSearch(request(null, null, null, null, null), null);
        assertFalse(queryCaptor.getValue().getFilters().toString().contains("initiatorId"));
    }

    @Test
    void createdRangeBoundsAreApplied() {
        runSearch(request(null,
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 14, 0, 0), null, null), null);
        String filters = queryCaptor.getValue().getFilters().toString();
        assertTrue(filters.contains("createdTime"));
        assertTrue(filters.indexOf("createdTime") != filters.lastIndexOf("createdTime"),
                "both bounds should produce a createdTime filter each");
    }

    @Test
    void theCallersOwnListKeepsTheirRecordApprovalsOnly() {
        runSearch(request(null, null, null, null, null), "u1");
        assertTrue(queryCaptor.getValue().getFilters().toString().contains("recordApproval"));
    }

    @Test
    void theMonitorListKeepsEveryInstance() {
        runSearch(request("u1", null, null, null, null), null);
        assertFalse(queryCaptor.getValue().getFilters().toString().contains("recordApproval"));
    }

    @Test
    void awaitingRevisionKeepsTheReturnedApprovalsNoLaterSubmissionReplaced() {
        FlowInstanceSearchRequest request = new FlowInstanceSearchRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null);
        runSearch(request, "u1");
        String filters = queryCaptor.getValue().getFilters().toString();
        assertTrue(filters.contains("Returned") && filters.contains("superseded"), filters);
    }

    @Test
    void summaryProjectionExcludesJsonStateColumns() {
        runSearch(request(null, null, null, null, null), null);
        List<String> fields = queryCaptor.getValue().getFields();
        assertEquals(24, fields.size());
        assertTrue(fields.containsAll(List.of("id", "instanceId", "bundleId", "flowId", "flowName", "scenario",
                "flowRevision", "title", "modelName", "rowId", "initiatorId", "recordApproval", "status",
                "failedNodeId", "retryAfterNodeId", "nextFireAt", "createdTime", "updatedTime",
                "subjectId", "departmentId", "effectiveDate", "recordCode", "summary", "superseded")));
        assertFalse(fields.contains("variables"));
        assertFalse(fields.contains("inputPayload"));
        assertFalse(fields.contains("pendingApprovals"));
        assertFalse(fields.contains("waitTokens"));
    }

    /** The task lists name each task's request from its instance, the summary line among it (zingkey-hcm#2350). */
    @Test
    void theInstancesTheTaskListsReadCarryTheirSummary() {
        doReturn(List.of()).when(service).searchList(any(FlexQuery.class));

        service.findByInstanceIds(List.of("i1"));

        verify(service).searchList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getFields().contains("summary"));
    }

    @Test
    void theCallersOwnSearchAndsTheirFiltersOntoTheirPinAndTheTypedFilters() {
        try (MockedStatic<ModelManager> ignored = StoredFieldsFixture.mockModelManager(FlowInstance.class)) {
            runSearch(read("""
                    {"recordCode": "EXP",
                     "filters": [["title", "CONTAINS", "Taxi"], "OR", ["initiatorId", "=", "boss"]]}
                    """), "me");
        }
        assertEquals(List.of("my taxi claim"), matched(),
                "the boss's approvals and the caller's automation runs stay out, however the filters are put");
        assertEquals("id DESC", queryCaptor.getValue().getOrders().toString(), "newest first, unchanged");
    }

    @Test
    void theMonitorSearchAndsTheCallersFiltersOntoItsOwn() {
        try (MockedStatic<ModelManager> ignored = StoredFieldsFixture.mockModelManager(FlowInstance.class)) {
            runSearch(read("""
                    {"initiatorId": "boss", "filters": ["title", "CONTAINS", "Taxi"],
                     "orders": [["effectiveDate", "ASC"]]}
                    """), null);
        }
        assertEquals(List.of("boss's taxi claim"), matched());
        assertEquals("effectiveDate ASC, id ASC", queryCaptor.getValue().getOrders().toString(),
                "the caller's order, then the id so a page never splits rows tied on it");
    }

    @Test
    void aFieldTheInstanceDoesNotStoreIsRefusedOnEitherSearch() {
        doReturn(Page.<FlowInstance>of(1, 50)).when(service).searchPage(any(FlexQuery.class), any());
        try (MockedStatic<ModelManager> ignored = StoredFieldsFixture.mockModelManager(FlowInstance.class)) {
            FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                    () -> service.searchSummaries(read("{\"filters\": [\"flowName\", \"=\", \"Expense\"]}"), "me"));
            assertEquals("Cannot filter by 'flowName': it is not a stored field of FlowInstance", refused.getMessage());
            assertThrows(FlowActionValidationException.class,
                    () -> service.searchSummaries(read("{\"filters\": [\"flowId.flowName\", \"=\", \"x\"]}"), null));
            assertThrows(FlowActionValidationException.class,
                    () -> service.searchSummaries(read("{\"orders\": [[\"scenario\", \"ASC\"]]}"), null));
        }
        verify(service, never()).searchPage(any(FlexQuery.class), any());
    }

    /** The approvals of two people, as rows: what the query keeps of them, by name. */
    private List<String> matched() {
        Map<String, Map<String, Object>> rows = Map.of(
                "my taxi claim", instanceRow("me", true, "Taxi to the airport", "EXP-1"),
                "my hotel claim", instanceRow("me", true, "Hotel in Auckland", "EXP-2"),
                "my taxi automation run", instanceRow("me", false, "Taxi sync", "EXP-3"),
                "my uncoded taxi", instanceRow("me", true, "Taxi home", "PAY-4"),
                "boss's taxi claim", instanceRow("boss", true, "Taxi for the boss", "EXP-5"),
                "boss's hotel claim", instanceRow("boss", true, "Hotel for the boss", "EXP-6"));
        FilterEvaluator.EvalContext now = new FilterEvaluator.EvalContext(null, null, LocalDate.now(),
                LocalDateTime.now());
        return rows.entrySet().stream()
                .filter(entry -> FilterEvaluator.matches(queryCaptor.getValue().getFilters(), entry.getValue(), now,
                        field -> null))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Map<String, Object> instanceRow(String initiatorId, boolean recordApproval, String title,
                                                   String recordCode) {
        return Map.of("initiatorId", initiatorId, "recordApproval", recordApproval, "title", title,
                "recordCode", recordCode);
    }

    /** A search request as a client posts it, read as the runtime reads a body. */
    private static FlowInstanceSearchRequest read(String body) {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().globalJacksonPolicy().customize(builder);
        return builder.build().readValue(body, FlowInstanceSearchRequest.class);
    }

    @Test
    void pagingDefaultsToFirstPageOfFifty() {
        runSearch(request(null, null, null, null, null), null);
        assertEquals(1, pageCaptor.getValue().getPageNumber());
        assertEquals(50, pageCaptor.getValue().getPageSize());
    }

    @Test
    void explicitPagingIsHonored() {
        runSearch(request(null, null, null, 3, 20), null);
        assertEquals(3, pageCaptor.getValue().getPageNumber());
        assertEquals(20, pageCaptor.getValue().getPageSize());
    }
}
