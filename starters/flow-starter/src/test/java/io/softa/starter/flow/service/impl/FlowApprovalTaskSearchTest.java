package io.softa.starter.flow.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.config.JacksonConfig;
import io.softa.framework.orm.domain.FilterEvaluator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.dto.FlowApprovalTaskSearchRequest;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowApprovalTaskBox;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.query.TaskInstanceContextEnricher;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;
import io.softa.starter.flow.service.support.StoredFieldsFixture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FlowApprovalTaskServiceImpl#searchTasks}: the approval center's lists. A box selects the caller's own tasks
 * as the pending, completed and CC reads did; the caller's filters narrow them and cannot reach anyone else's; their
 * orders replace the box's; and a name a task does not store is refused.
 */
class FlowApprovalTaskSearchTest {

    private static final FilterEvaluator.EvalContext NOW =
            new FilterEvaluator.EvalContext(null, null, LocalDate.now(), LocalDateTime.now());

    private static final JsonMapper RUNTIME = runtimeMapper();

    /** The tasks of two people, as rows: what the query built for "me" must keep exactly the right ones. */
    private static final Map<String, Map<String, Object>> ROWS = Map.of(
            "my pending taxi", row("me", "Pending", "Approval", "Taxi to the airport", "EXP-1"),
            "my pending hotel", row("me", "Pending", "Approval", "Hotel in Auckland", "EXP-2"),
            "my approved taxi", row("me", "Approved", "Approval", "Taxi home", "EXP-3"),
            "my returned taxi", row("me", "Returned", "Approval", "Taxi again", "EXP-4"),
            "my unread cc", row("me", "Pending", "Cc", "Taxi for the team", "EXP-5"),
            "my read cc", row("me", "Read", "Cc", "Hotel for the team", "EXP-6"),
            "boss's pending taxi", row("boss", "Pending", "Approval", "Taxi for the boss", "EXP-7"),
            "boss's cc", row("boss", "Pending", "Cc", "Taxi copied to the boss", "EXP-8"));

    private final ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
    private final FlowInstanceService enrichingInstances = mock(FlowInstanceService.class);
    private MockedStatic<ModelManager> models;
    private FlowApprovalTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        models = StoredFieldsFixture.mockModelManager(FlowApprovalTask.class);
        ApprovalAuditReader reader = new ApprovalAuditReader(new NoopApprovalActionLedger());
        service = spy(new FlowApprovalTaskServiceImpl(new FlowApprovalTaskProjector(reader),
                mock(FlowInstanceAccessGuard.class), reader,
                new TaskInstanceContextEnricher(enrichingInstances, mock(FlowUserNames.class),
                        mock(FlowBundleRegistry.class)),
                mock(FlowInstanceService.class)));
        Page<FlowApprovalTask> page = Page.of(1, 50);
        page.setRows(List.of(task()));
        page.setTotalCount(1);
        doReturn(page).when(service).searchPage(any(FlexQuery.class), any());
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void theBoxesSelectTheCallersTasksInTheirOrder() {
        search(FlowApprovalTaskBox.PENDING, null, null);
        assertEquals(List.of("my pending hotel", "my pending taxi"), matched());
        assertEquals("startTime DESC, id DESC", query.getValue().getOrders().toString());

        search(FlowApprovalTaskBox.HANDLED, null, null);
        assertEquals(List.of("my approved taxi", "my returned taxi"), matched());
        assertEquals("endTime DESC, id DESC", query.getValue().getOrders().toString());

        search(FlowApprovalTaskBox.CC, null, null);
        assertEquals(List.of("my read cc", "my unread cc"), matched(), "read and unread");
        assertEquals("startTime DESC, id DESC", query.getValue().getOrders().toString(), "newest first, as Pending");
    }

    @Test
    void theCallersFiltersNarrowTheBoxAndTheirOrdersReplaceItsOrder() {
        search(FlowApprovalTaskBox.PENDING,
                "[[\"instanceTitle\", \"CONTAINS\", \"Taxi\"], \"OR\", [\"recordCode\", \"CONTAINS\", \"Taxi\"]]",
                "[[\"submittedTime\", \"DESC\"]]");
        assertEquals(List.of("my pending taxi"), matched());
        assertEquals("submittedTime DESC, id DESC", query.getValue().getOrders().toString());

        search(FlowApprovalTaskBox.CC, "[\"searchName\", \"CONTAINS\", \"Hotel\"]", null);
        assertTrue(query.getValue().getFilters().toString().contains("searchName"), "the toolbar search is kept");
    }

    @Test
    void filtersNamingAnotherActorStillFindOnlyTheCallersTasks() {
        search(FlowApprovalTaskBox.PENDING, "[\"actorId\", \"=\", \"boss\"]", null);
        assertEquals(List.of(), matched(), "the boss's tasks are not the caller's to search");

        search(FlowApprovalTaskBox.PENDING,
                "[[\"actorId\", \"=\", \"boss\"], \"OR\", [\"instanceTitle\", \"CONTAINS\", \"Taxi\"]]", null);
        assertEquals(List.of("my pending taxi"), matched(), "an OR in the filters stays inside them");

        search(FlowApprovalTaskBox.CC, "[[\"actorId\", \"!=\", \"me\"], \"OR\", [\"taskType\", \"=\", \"Approval\"]]",
                null);
        assertEquals(List.of(), matched());
    }

    @Test
    void aNameATaskDoesNotStoreIsRefusedBeforeAnythingIsRead() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.searchTasks("me", request(FlowApprovalTaskBox.HANDLED,
                        "[\"flowName\", \"CONTAINS\", \"Expense\"]", null)));
        assertEquals("Cannot filter by 'flowName': it is not a stored field of FlowApprovalTask", refused.getMessage());
        assertThrows(FlowActionValidationException.class, () -> service.searchTasks("me",
                request(FlowApprovalTaskBox.PENDING, null, "[[\"initiator\", \"ASC\"]]")));
        assertThrows(FlowActionValidationException.class, () -> service.searchTasks("me",
                request(FlowApprovalTaskBox.PENDING, "[\"flowId.flowName\", \"=\", \"Expense\"]", null)));
        verify(service, never()).searchPage(any(FlexQuery.class), any());
    }

    @Test
    void aSearchWithoutABoxIsRefused() {
        assertThrows(FlowActionValidationException.class,
                () -> service.searchTasks("me", new FlowApprovalTaskSearchRequest(null, null, null, null, null)));
        assertThrows(FlowActionValidationException.class, () -> service.searchTasks("me", null));
    }

    @Test
    void theRowsComeAsViewsEnrichedWithTheirInstanceOnTheRequestedPage() {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId("i1");
        instance.setTitle("Taxi to the airport");
        instance.setRecordCode("EXP-1");
        instance.setInitiatorId("u-7");
        instance.setStatus(FlowExecutionStatus.WAITING);
        when(enrichingInstances.findByInstanceIds(List.of("i1"))).thenReturn(List.of(instance));

        Page<FlowApprovalTaskView> page = service.searchTasks("me",
                new FlowApprovalTaskSearchRequest(FlowApprovalTaskBox.PENDING, null, null, 2, 20));

        assertEquals(2, page.getPageNumber());
        assertEquals(20, page.getPageSize());
        assertEquals(1, page.getTotalCount());
        FlowApprovalTaskView view = page.getRows().getFirst();
        assertEquals("Taxi to the airport", view.getInstanceTitle());
        assertEquals(FlowExecutionStatus.WAITING, view.getInstanceStatus());
        assertEquals("u-7", view.getInitiator().getId());
    }

    @Test
    void aTaskWhoseInstanceIsGoneStillShowsWhatItStoredOfIt() {
        Page<FlowApprovalTaskView> page = service.searchTasks("me",
                new FlowApprovalTaskSearchRequest(FlowApprovalTaskBox.PENDING, null, null, null, null));

        FlowApprovalTaskView view = page.getRows().getFirst();
        assertEquals("Taxi to the airport", view.getInstanceTitle());
        assertEquals("EXP-1", view.getRecordCode());
        assertNull(view.getInstanceStatus());
    }

    private void search(FlowApprovalTaskBox box, String filters, String orders) {
        clearInvocations(service);
        service.searchTasks("me", request(box, filters, orders));
        verify(service).searchPage(query.capture(), any());
    }

    /** The request as the front end posts it, read the way the runtime reads a body. */
    private static FlowApprovalTaskSearchRequest request(FlowApprovalTaskBox box, String filters, String orders) {
        String body = "{\"box\": \"" + box.getType() + "\""
                + (filters == null ? "" : ", \"filters\": " + filters)
                + (orders == null ? "" : ", \"orders\": " + orders) + "}";
        return RUNTIME.readValue(body, FlowApprovalTaskSearchRequest.class);
    }

    private static JsonMapper runtimeMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().globalJacksonPolicy().customize(builder);
        return builder.build();
    }

    /** The rows the last query keeps, by name, sorted. */
    private List<String> matched() {
        Filters filters = query.getValue().getFilters();
        return ROWS.entrySet().stream()
                .filter(entry -> FilterEvaluator.matches(filters, entry.getValue(), NOW, field -> null))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Map<String, Object> row(String actorId, String status, String taskType, String instanceTitle,
                                           String recordCode) {
        return Map.of("actorId", actorId, "status", status, "taskType", taskType, "instanceTitle", instanceTitle,
                "recordCode", recordCode);
    }

    private static FlowApprovalTask task() {
        FlowApprovalTask task = new FlowApprovalTask();
        task.setId(1L);
        task.setInstanceId("i1");
        task.setNodeId("manager");
        task.setActorId("me");
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setStatus(FlowApprovalTaskStatus.PENDING);
        task.setInstanceTitle("Taxi to the airport");
        task.setRecordCode("EXP-1");
        return task;
    }
}
