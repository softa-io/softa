package io.softa.starter.flow.service.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.enums.LogicOperator;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conditions a caller adds to a flow list: only the fields the model stores, ANDed onto the list's own, sorted
 * stably.
 */
class CallerConditionsTest {

    private static final String TASK = FlowApprovalTask.class.getSimpleName();
    private static final String INSTANCE = FlowInstance.class.getSimpleName();

    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        models = StoredFieldsFixture.mockModelManager(FlowApprovalTask.class, FlowInstance.class);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void storedFieldsTheToolbarSearchAndFieldComparisonsBetweenThemPass() {
        assertDoesNotThrow(() -> CallerConditions.requireStoredFields(TASK, Filters.of("""
                [[["instanceTitle", "CONTAINS", "taxi"], "OR", ["recordCode", "CONTAINS", "taxi"]],
                 ["searchName", "CONTAINS", "taxi"], ["submittedTime", ">=", "2026-10-01 00:00:00"],
                 ["endTime", ">", "{{ @startTime }}"], ["departmentId", "IN", [30, 31]], ["createdTime", "IS SET", null]]
                """), Orders.of("submittedTime DESC, recordCode ASC")));
        assertDoesNotThrow(() -> CallerConditions.requireStoredFields(INSTANCE,
                Filters.of("[\"title\", \"CONTAINS\", \"taxi\"]"), Orders.of("effectiveDate ASC")));
        assertDoesNotThrow(() -> CallerConditions.requireStoredFields(TASK, null, null));
    }

    @Test
    void aDynamicFieldIsRefusedByItsName() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> CallerConditions.requireStoredFields(TASK,
                        Filters.of("[\"flowName\", \"CONTAINS\", \"Expense\"]"), null));
        assertEquals("Cannot filter by 'flowName': it is not a stored field of FlowApprovalTask", refused.getMessage());
        assertThrows(FlowActionValidationException.class,
                () -> CallerConditions.requireStoredFields(INSTANCE, Filters.of("[\"scenario\", \"=\", \"Approval\"]"), null));
    }

    @Test
    void aPathIntoAnotherModelAnUnknownNameAndAComparisonWithAnUnstoredFieldAreRefused() {
        assertRefused(TASK, "[\"flowId.flowName\", \"=\", \"Expense\"]", "flowId.flowName");
        assertRefused(TASK, "[[\"actorId\", \"=\", \"u1\"], \"OR\", [\"title\", \"=\", \"x\"]]", "title");
        assertRefused(TASK, "[\"nodeLabel\", \"=\", \"{{ @flowName }}\"]", "flowName");
    }

    @Test
    void anOrderOnAnythingButAStoredFieldIsRefusedAndTheToolbarSearchIsNoOrder() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> CallerConditions.requireStoredFields(TASK, null, Orders.of("submittedTime DESC, flowName ASC")));
        assertTrue(refused.getMessage().contains("sort by 'flowName'"), refused.getMessage());
        assertThrows(FlowActionValidationException.class,
                () -> CallerConditions.requireStoredFields(TASK, null, Orders.of("searchName")));
    }

    @Test
    void theCallersFiltersAreAndedOnAsTheirOwnSubtree() {
        Filters own = new Filters().eq(FlowApprovalTask::getActorId, "me").eq(FlowApprovalTask::getTaskType, "Cc");
        Filters theirs = Filters.of("[[\"actorId\", \"=\", \"boss\"], \"OR\", [\"recordCode\", \"=\", \"EXP-1\"]]");

        Filters combined = CallerConditions.and(own, theirs);

        assertEquals(FilterType.TREE, combined.getType());
        assertEquals(LogicOperator.AND, combined.getLogicOperator());
        assertSame(own, combined.getChildren().get(0));
        assertSame(theirs, combined.getChildren().get(1));
        assertSame(own, CallerConditions.and(own, null), "no filters of theirs: the list's own");
        assertSame(own, CallerConditions.and(own, new Filters()));
    }

    @Test
    void theCallersOrdersReplaceTheDefaultAndEndOnTheIdInTheDirectionOfTheirFirst() {
        Orders defaults = Orders.ofDesc("startTime").addDesc("id");

        assertSame(defaults, CallerConditions.ordersOr(null, defaults));
        assertEquals("submittedTime DESC, recordCode ASC, id DESC",
                CallerConditions.ordersOr(Orders.of("submittedTime DESC, recordCode ASC"), defaults).toString());
        assertEquals("effectiveDate ASC, id ASC",
                CallerConditions.ordersOr(Orders.of("effectiveDate"), defaults).toString());
        assertEquals("id DESC, submittedTime ASC",
                CallerConditions.ordersOr(Orders.of("id DESC, submittedTime ASC"), defaults).toString(),
                "already sorted by id: nothing added");
    }

    private static void assertRefused(String model, String filters, String field) {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> CallerConditions.requireStoredFields(model, Filters.of(filters), null));
        assertTrue(refused.getMessage().contains("'" + field + "'"), refused.getMessage());
    }
}
