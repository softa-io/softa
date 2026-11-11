package io.softa.starter.message.inbox.controller;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.enums.LogicOperator;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.message.inbox.dto.InboxSearchRequest;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.service.InboxNotificationService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The Notifications page reads the caller's own notifications, past row scope: what the request adds narrows and
 * sorts them, and may name only what the model stores.
 */
class InboxMySearchTest {

    private static final Long CALLER = 42L;

    private InboxNotificationService service;
    private InboxNotificationController controller;
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        service = mock(InboxNotificationService.class);
        controller = new InboxNotificationController();
        ReflectionTestUtils.setField(controller, "inboxNotificationService", service);
        models = mockStatic(ModelManager.class);
        String model = InboxNotification.class.getSimpleName();
        for (Class<?> type = InboxNotification.class; type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                Field declared = field.getAnnotation(Field.class);
                if (declared != null) {
                    String name = field.getName();
                    models.when(() -> ModelManager.existField(model, name)).thenReturn(true);
                    models.when(() -> ModelManager.isStored(model, name)).thenReturn(!declared.dynamic());
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void theCallersFiltersNarrowTheirOwnNotificationsNewestFirst() {
        Filters theirs = Filters.of("[[\"recipientId\", \"=\", 7], \"OR\", [\"searchName\", \"CONTAINS\", \"leave\"]]");

        FlexQuery query = search(new InboxSearchRequest(theirs, null, 2, 20));

        Filters filters = query.getFilters();
        assertEquals(FilterType.TREE, filters.getType());
        assertEquals(LogicOperator.AND, filters.getLogicOperator());
        String mine = filters.getChildren().getFirst().toString();
        assertTrue(mine.contains("recipientId") && mine.contains(String.valueOf(CALLER)), mine);
        assertSame(theirs, filters.getChildren().get(1), "an OR of theirs stays in its own subtree");
        assertEquals("createdTime DESC, id DESC", query.getOrders().toString());
    }

    @Test
    void aSortOfTheirsEndsOnTheIdInItsDirection() {
        assertEquals("isRead ASC, id ASC",
                search(new InboxSearchRequest(null, Orders.of("isRead ASC"), null, null)).getOrders().toString());
        assertEquals("id DESC", search(new InboxSearchRequest(null, Orders.of("id DESC"), null, null))
                .getOrders().toString());
    }

    @Test
    void anythingButAStoredFieldIsRefused() {
        assertRefused(new InboxSearchRequest(Filters.of("[\"createdId.password\", \"=\", \"x\"]"), null, null, null));
        assertRefused(new InboxSearchRequest(Filters.of("[\"title\", \"=\", \"{{ @secret }}\"]"), null, null, null));
        assertRefused(new InboxSearchRequest(null, Orders.of("searchName"), null, null));
        verifyNoInteractions(service);
    }

    @Test
    void markingByIdsIsBoundToTheCaller() {
        when(service.markReadByIds(CALLER, List.of(1L, 2L))).thenReturn(1);

        Integer marked = ContextHolder.callWith(caller(), () -> controller.myMarkAsReadByIds(List.of(1L, 2L)).getData());

        assertEquals(1, marked);
        verify(service).markReadByIds(CALLER, List.of(1L, 2L));
    }

    @SuppressWarnings("unchecked")
    private FlexQuery search(InboxSearchRequest request) {
        when(service.searchPage(any(FlexQuery.class), any(Page.class))).thenAnswer(call -> call.getArgument(1));
        ContextHolder.runWith(caller(), () -> controller.mySearch(request));
        ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
        verify(service, atLeastOnce()).searchPage(query.capture(), any(Page.class));
        clearInvocations(service);
        return query.getValue();
    }

    private void assertRefused(InboxSearchRequest request) {
        assertThrows(IllegalArgumentException.class, () -> ContextHolder.runWith(caller(), () -> controller.mySearch(request)));
    }

    private static Context caller() {
        Context context = new Context();
        context.setUserId(CALLER);
        return context;
    }
}
