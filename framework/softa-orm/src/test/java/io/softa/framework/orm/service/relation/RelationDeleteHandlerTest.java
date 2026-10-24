package io.softa.framework.orm.service.relation;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RelationDeleteHandler}. Drives the policy branching with mocked
 * statics ({@code ModelManager} reverse index + flags, {@code ReflectTool} service calls) — no DB.
 * The deleted "One" is modelled as multi-tenant so the cross-tenant window is not opened (kept simple).
 */
class RelationDeleteHandlerTest {

    private final RelationDeleteHandler handler = new RelationDeleteHandler();

    /** One inbound FK on "Order".fkField → "Customer", with the given policy; id-based join. */
    private static MetaField inboundField(OnDelete onDelete) {
        MetaField f = mock(MetaField.class);
        when(f.getOnDelete()).thenReturn(onDelete);
        when(f.getModelName()).thenReturn("Order");
        when(f.getFieldName()).thenReturn("customerId");
        return f;
    }

    private static void stubModelManager(MockedStatic<ModelManager> mm, MetaField inbound) {
        // getOnDeleteRefFields() is an instance method on MetaModel — stub it on the mock, NOT as a
        // chained mockStatic call (which would mis-capture the static getModel call).
        MetaModel customer = mock(MetaModel.class);
        when(customer.getOnDeleteRefFields()).thenReturn(List.of(inbound));
        when(customer.isMultiTenant()).thenReturn(true);   // not shared → no cross-tenant window
        mm.when(() -> ModelManager.getModel("Customer")).thenReturn(customer);
        mm.when(() -> ModelManager.isActiveControl("Order")).thenReturn(false);
    }

    private static final List<Serializable> ONE_ID = List.of(7L);

    @Test
    void restrict_withReferrers_throwsAndRollsBack() {
        MetaField f = inboundField(OnDelete.RESTRICT);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            rt.when(() -> ReflectTool.count(eq("Order"), any(Filters.class))).thenReturn(3L);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> handler.handle("Customer", ONE_ID));
            assertTrue(ex.getMessage().contains("RESTRICT"), "message names the policy: " + ex.getMessage());
            rt.verify(() -> ReflectTool.deleteList(any(), anyList()), never());
        }
    }

    @Test
    void restrict_noReferrers_passes() {
        MetaField f = inboundField(OnDelete.RESTRICT);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            rt.when(() -> ReflectTool.count(eq("Order"), any(Filters.class))).thenReturn(0L);

            handler.handle("Customer", ONE_ID);   // no throw
            rt.verify(() -> ReflectTool.deleteList(any(), anyList()), never());
        }
    }

    @Test
    void cascade_deletesReferrers() {
        MetaField f = inboundField(OnDelete.CASCADE);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            rt.when(() -> ReflectTool.getIds(eq("Order"), any(Filters.class), anyInt()))
                    .thenReturn(List.of((Serializable) 101L, 102L));

            handler.handle("Customer", ONE_ID);

            rt.verify(() -> ReflectTool.deleteList("Order", List.of(101L, 102L)), times(1));
        }
    }

    @Test
    void cascade_overMaxBatchSize_rejected() {
        // Tier 2 volume cap: referrerIds fetches LIMIT MAX_BATCH_SIZE+1; a set that big fails fast
        // (bounded — the full set is never loaded) before any delete.
        MetaField f = inboundField(OnDelete.CASCADE);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            rt.when(() -> ReflectTool.getIds(eq("Order"), any(Filters.class), anyInt()))
                    .thenReturn(Collections.nCopies(BaseConstant.MAX_BATCH_SIZE + 1, (Serializable) 1L));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> handler.handle("Customer", ONE_ID));
            assertTrue(ex.getMessage().contains("MAX_BATCH_SIZE"), "message names the limit: " + ex.getMessage());
            rt.verify(() -> ReflectTool.deleteList(any(), anyList()), never());   // rejected before any delete
        }
    }

    @Test
    void setNull_hardDelete_nullsFk() {
        MetaField f = inboundField(OnDelete.SET_NULL);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            mm.when(() -> ModelManager.isSoftDeleted("Customer")).thenReturn(false);   // hard delete
            rt.when(() -> ReflectTool.getIds(eq("Order"), any(Filters.class), anyInt()))
                    .thenReturn(List.of((Serializable) 101L));

            handler.handle("Customer", ONE_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
            rt.verify(() -> ReflectTool.updateList(eq("Order"), captor.capture()), times(1));
            Map<String, Object> update = captor.getValue().getFirst();
            assertEquals(101L, update.get("id"));
            assertTrue(update.containsKey("customerId"));
            assertNull(update.get("customerId"), "FK is set to null");
        }
    }

    @Test
    void setNull_softDelete_isNoOp() {
        MetaField f = inboundField(OnDelete.SET_NULL);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            mm.when(() -> ModelManager.isSoftDeleted("Customer")).thenReturn(true);    // soft delete → keep link

            handler.handle("Customer", ONE_ID);

            rt.verify(() -> ReflectTool.getIds(any(), any(), anyInt()), never());
            rt.verify(() -> ReflectTool.updateList(any(), anyList()), never());
        }
    }

    @Test
    void noInboundRefs_isNoOp() {
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            MetaModel customer = mock(MetaModel.class);
            when(customer.getOnDeleteRefFields()).thenReturn(List.of());
            mm.when(() -> ModelManager.getModel("Customer")).thenReturn(customer);

            handler.handle("Customer", ONE_ID);

            rt.verifyNoInteractions();   // no inbound onDelete refs → no scan / cascade / set-null
        }
    }

    @Test
    void referrersAreProbedAsTheSystem() {
        // Whether a row is still referenced does not depend on who asks: read as the caller, a referrer
        // outside their range — or shown to them by its label only — did not count, and the delete went
        // through leaving it pointing at nothing.
        MetaField f = inboundField(OnDelete.RESTRICT);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            rt.when(() -> ReflectTool.count(eq("Order"), any(Filters.class))).thenAnswer(inv ->
                    ContextHolder.getContext().isSkipPermissionCheck() ? 1L : 0L);

            ContextHolder.runWith(new Context(), () ->
                    assertThrows(RuntimeException.class, () -> handler.handle("Customer", ONE_ID)));
        }
    }

    @Test
    void cascadeReferrersAreCollectedAsTheSystem() {
        MetaField f = inboundField(OnDelete.CASCADE);
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> rt = mockStatic(ReflectTool.class)) {
            stubModelManager(mm, f);
            rt.when(() -> ReflectTool.getIds(eq("Order"), any(Filters.class), anyInt())).thenAnswer(inv ->
                    ContextHolder.getContext().isSkipPermissionCheck() ? List.of((Serializable) 101L) : List.of());

            ContextHolder.runWith(new Context(), () -> handler.handle("Customer", ONE_ID));

            rt.verify(() -> ReflectTool.deleteList("Order", List.of(101L)), times(1));
        }
    }
}
