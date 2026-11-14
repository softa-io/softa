package io.softa.starter.flow.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.softa.starter.flow.dto.FlowDelegationMonitorSearchRequest;
import io.softa.starter.flow.dto.FlowDelegationSearchRequest;
import io.softa.starter.flow.dto.FlowDelegationView;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.enums.FlowDelegationBox;
import io.softa.starter.flow.enums.FlowDelegationStatus;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.support.FlowUserNames;
import io.softa.starter.flow.service.support.StoredFieldsFixture;
import io.softa.starter.flow.service.support.view.FlowDelegationViews;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FlowDelegationServiceImpl#searchDelegations}: the Delegations page's lists. Mine holds every rule routing the
 * caller's tasks, whatever its status; To me holds the rules routing others' tasks to them now or later, as a
 * condition on the stored rule, so a page holds what the list shows. The caller's filters narrow a box and cannot
 * reach anyone else's rules, their orders replace newest first, and a name the rule does not store is refused. The
 * lookup that routes a task, and the lists' status, take a rule as active alike.
 *
 * <p>{@link FlowDelegationServiceImpl#searchAll}: the administrators' list, every rule of the tenant. A filter on
 * {@code status} holds exactly the rules {@link FlowDelegationStatus#of} gives that status, wherever it stands in the
 * filters.
 */
class FlowDelegationSearchTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    private static final FilterEvaluator.EvalContext CONTEXT =
            new FilterEvaluator.EvalContext(null, null, LocalDate.now(), NOW);

    private static final JsonMapper RUNTIME = runtimeMapper();

    /** The rules of three people, as rows: what the query built for "me" must keep exactly the right ones. */
    private static final Map<String, Map<String, Object>> ROWS = Map.of(
            "mine, open-ended", row("me", "bob", true, null, null),
            "mine, scheduled", row("me", "bob", true, NOW.plusDays(2), NOW.plusDays(9)),
            "mine, ended", row("me", "bob", true, NOW.minusDays(9), NOW.minusDays(2)),
            "mine, cancelled", row("me", "bob", false, null, null),
            "to me, active until next week", row("ann", "me", true, NOW.minusDays(1), NOW.plusDays(7)),
            "to me, scheduled", row("ann", "me", true, NOW.plusDays(2), null),
            "to me, ended", row("ann", "me", true, null, NOW.minusDays(1)),
            "to me, cancelled", row("ann", "me", false, null, null),
            "between others", row("ann", "bob", true, null, null));

    private final ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
    private MockedStatic<ModelManager> models;
    private FlowDelegationServiceImpl service;

    @BeforeEach
    void setUp() {
        models = StoredFieldsFixture.mockModelManager(FlowDelegation.class);
        service = spy(new FlowDelegationServiceImpl());
        doReturn(Page.of(1, 50)).when(service).searchPage(any(FlexQuery.class), any());
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void mineHoldsEveryRuleRoutingTheCallersTasksNewestFirst() {
        search(FlowDelegationBox.MINE, null, null);

        assertEquals(List.of("mine, cancelled", "mine, ended", "mine, open-ended", "mine, scheduled"), matched());
        assertEquals("id DESC", query.getValue().getOrders().toString());
    }

    @Test
    void toMeHoldsTheRulesRoutingOthersTasksToTheCallerNowOrLater() {
        search(FlowDelegationBox.TO_ME, null, null);

        assertEquals(List.of("to me, active until next week", "to me, scheduled"), matched());
        assertEquals("id DESC", query.getValue().getOrders().toString());
    }

    @Test
    void theCallersFiltersNarrowTheBoxAndCannotReachAnyoneElsesRules() {
        search(FlowDelegationBox.MINE, "[\"delegatorId\", \"=\", \"ann\"]", null);
        assertEquals(List.of(), matched(), "ann's rules are not the caller's to search");

        search(FlowDelegationBox.TO_ME, "[[\"delegatorId\", \"=\", \"ann\"], \"OR\", [\"delegateId\", \"=\", \"bob\"]]",
                null);
        assertEquals(List.of("to me, active until next week", "to me, scheduled"), matched(),
                "an OR in the filters stays inside them");
    }

    @Test
    void theCallersOrdersReplaceNewestFirst() {
        search(FlowDelegationBox.MINE, null, "[[\"startTime\", \"ASC\"]]");

        assertEquals("startTime ASC, id ASC", query.getValue().getOrders().toString());
    }

    @Test
    void aNameTheRuleDoesNotStoreIsRefusedBeforeAnythingIsRead() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.searchDelegations("me", request(FlowDelegationBox.MINE,
                        "[\"flowName\", \"CONTAINS\", \"Leave\"]", null)));
        assertEquals("Cannot filter by 'flowName': it is not a stored field of FlowDelegation", refused.getMessage());
        assertThrows(FlowActionValidationException.class, () -> service.searchDelegations("me",
                request(FlowDelegationBox.TO_ME, null, "[[\"status\", \"ASC\"]]")));
        verify(service, never()).searchPage(any(FlexQuery.class), any());
    }

    @Test
    void aSearchWithoutABoxIsRefused() {
        assertThrows(FlowActionValidationException.class,
                () -> service.searchDelegations("me", new FlowDelegationSearchRequest(null, null, null, null, null)));
        assertThrows(FlowActionValidationException.class, () -> service.searchDelegations("me", null));
    }

    @Test
    void theAdministratorsListHoldsEveryRuleOfTheTenantNewestFirst() {
        searchAll(null, null);

        assertEquals(ROWS.keySet().stream().sorted().toList(), matched());
        assertEquals("id DESC", query.getValue().getOrders().toString());

        searchAll(null, "[[\"endTime\", \"DESC\"]]");
        assertEquals("endTime DESC, id DESC", query.getValue().getOrders().toString());
    }

    @Test
    void aStatusHoldsExactlyTheRulesShowingItNow() {
        for (FlowDelegationStatus status : FlowDelegationStatus.values()) {
            searchAll("[\"status\", \"=\", \"" + status.getType() + "\"]", null);
            assertEquals(showing(status), matched(), status.getType());
        }
    }

    @Test
    void aStatusStandsWhereItIsInTheFilters() {
        searchAll("[[\"status\", \"=\", \"Active\"], \"AND\", [\"delegatorId\", \"=\", \"ann\"]]", null);
        assertEquals(List.of("between others", "to me, active until next week"), matched());

        searchAll("[[\"status\", \"=\", \"Cancelled\"], \"OR\", [\"delegateId\", \"=\", \"bob\"]]", null);
        assertEquals(List.of("between others", "mine, cancelled", "mine, ended", "mine, open-ended", "mine, scheduled",
                "to me, cancelled"), matched());
    }

    @Test
    void aStatusIsFilteredByWithEqualsAndOnlyByOneThereIs() {
        FlowActionValidationException unknown = assertThrows(FlowActionValidationException.class,
                () -> service.searchAll(monitorRequest("[\"status\", \"=\", \"Expired\"]", null)));
        assertEquals("No delegation status 'Expired': Scheduled, Active, Ended or Cancelled", unknown.getMessage());
        assertThrows(FlowActionValidationException.class,
                () -> service.searchAll(monitorRequest("[\"status\", \"IN\", [\"Active\", \"Scheduled\"]]", null)));
        assertThrows(FlowActionValidationException.class,
                () -> service.searchAll(monitorRequest(null, "[[\"status\", \"ASC\"]]")), "no column to sort on");
        assertThrows(FlowActionValidationException.class,
                () -> service.searchAll(monitorRequest("[\"flowName\", \"CONTAINS\", \"Leave\"]", null)));
        verify(service, never()).searchPage(any(FlexQuery.class), any());
    }

    @Test
    void theLookupRoutesOnlyByARuleActiveWhenTheTaskArrives() {
        FlowDelegation scheduledForTheStep = rule(1L, "Node", NOW.plusDays(1), null);
        FlowDelegation endedForTheFlow = rule(2L, "Flow", null, NOW.minusDays(1));
        FlowDelegation activeForAll = rule(3L, "All", NOW.minusDays(1), NOW.plusDays(1));
        doReturn(List.of(scheduledForTheStep, endedForTheFlow, activeForAll)).when(service).searchList(any(Filters.class));

        assertEquals(Optional.of(activeForAll), service.findActiveDelegation("me", "leave", "manager", NOW),
                "a more specific rule that is not active yet, or any more, does not win");

        doReturn(List.of(scheduledForTheStep, endedForTheFlow)).when(service).searchList(any(Filters.class));
        assertEquals(Optional.empty(), service.findActiveDelegation("me", "leave", "manager", NOW));
    }

    @Test
    void aPageOfRulesComesWithTheirStatusNumberedAsItWasRead() {
        Page<FlowDelegation> read = Page.of(2, 20);
        FlowDelegation madeOnTheirBehalf = rule(2L, "All", null, null);
        madeOnTheirBehalf.setCreatedBy("HR Admin");
        read.setRows(List.of(rule(1L, "All", NOW.plusDays(1), null), madeOnTheirBehalf));
        read.setTotalCount(22);

        Page<FlowDelegationView> page = new FlowDelegationViews(mock(FlowUserNames.class)).of(read);

        assertEquals(2, page.getPageNumber());
        assertEquals(20, page.getPageSize());
        assertEquals(22, page.getTotalCount());
        assertEquals(List.of(FlowDelegationStatus.SCHEDULED, FlowDelegationStatus.ACTIVE),
                page.getRows().stream().map(FlowDelegationView::getStatus).toList());
        assertEquals("HR Admin", page.getRows().get(1).getCreatedBy(), "who set the rule up");
    }

    private void search(FlowDelegationBox box, String filters, String orders) {
        clearInvocations(service);
        service.searchDelegations("me", request(box, filters, orders));
        verify(service).searchPage(query.capture(), any());
    }

    private void searchAll(String filters, String orders) {
        clearInvocations(service);
        service.searchAll(monitorRequest(filters, orders));
        verify(service).searchPage(query.capture(), any());
    }

    /** The request as the front end posts it, read the way the runtime reads a body. */
    private static FlowDelegationSearchRequest request(FlowDelegationBox box, String filters, String orders) {
        String body = "{\"box\": \"" + box.getType() + "\""
                + (filters == null ? "" : ", \"filters\": " + filters)
                + (orders == null ? "" : ", \"orders\": " + orders) + "}";
        return RUNTIME.readValue(body, FlowDelegationSearchRequest.class);
    }

    /** The administrators' request as the front end posts it. */
    private static FlowDelegationMonitorSearchRequest monitorRequest(String filters, String orders) {
        String body = "{\"pageNumber\": 1"
                + (filters == null ? "" : ", \"filters\": " + filters)
                + (orders == null ? "" : ", \"orders\": " + orders) + "}";
        return RUNTIME.readValue(body, FlowDelegationMonitorSearchRequest.class);
    }

    /** The rows whose rule {@link FlowDelegationStatus#of} gives the status now, by name, sorted. */
    private static List<String> showing(FlowDelegationStatus status) {
        return ROWS.entrySet().stream()
                .filter(entry -> FlowDelegationStatus.of(ruleOf(entry.getValue()), NOW) == status)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static FlowDelegation ruleOf(Map<String, Object> row) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setActive((Boolean) row.get("active"));
        delegation.setStartTime((LocalDateTime) row.get("startTime"));
        delegation.setEndTime((LocalDateTime) row.get("endTime"));
        return delegation;
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
                .filter(entry -> FilterEvaluator.matches(filters, entry.getValue(), CONTEXT, field -> null))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** A stored rule as a row; an open end of its period is a column left empty. */
    private static Map<String, Object> row(String delegatorId, String delegateId, boolean active,
                                           LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> row = new HashMap<>();
        row.put("delegatorId", delegatorId);
        row.put("delegateId", delegateId);
        row.put("active", active);
        row.put("startTime", startTime);
        row.put("endTime", endTime);
        return row;
    }

    private static FlowDelegation rule(Long id, String scope, LocalDateTime startTime, LocalDateTime endTime) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setId(id);
        delegation.setDelegatorId("me");
        delegation.setDelegateId("bob");
        delegation.setActive(true);
        delegation.setScope(scope);
        delegation.setFlowId("leave");
        delegation.setNodeId("manager");
        delegation.setStartTime(startTime);
        delegation.setEndTime(endTime);
        return delegation;
    }
}
