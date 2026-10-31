package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.user.dto.UiContext;
import io.softa.starter.user.service.impl.UiContextBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /me/uiContext} carries the sensitive fields the caller can never see, asked of the
 * permission engine per request — never the set catalogue behind them.
 */
class MeControllerHiddenFieldsTest {

    @SuppressWarnings("unchecked")
    private static UiContext uiContext(PermissionService permissionService) {
        CacheService cache = mock(CacheService.class);
        UiContext cached = new UiContext();
        cached.setRoleCodes(Set.of("HR"));
        when(cache.get(anyString(), any(Class.class))).thenReturn(cached);
        ObjectProvider<EntitlementService> entitlement = mock(ObjectProvider.class);
        ObjectProvider<PermissionService> permission = mock(ObjectProvider.class);
        when(permission.getIfAvailable()).thenReturn(permissionService);
        MeController controller = new MeController(cache, mock(UiContextBuilder.class), entitlement, permission);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, () -> controller.uiContext().getData());
    }

    @Test
    void theHiddenFieldsComeFromThePermissionEngine() {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields()).thenReturn(Map.of("Employee", Set.of("salary")));

        UiContext ui = uiContext(permissionService);

        assertThat(ui.getModelHiddenFieldsMap()).containsExactly(Map.entry("Employee", Set.of("salary")));
    }

    /**
     * The metadata leaves out a relation onto a model the caller can read nothing of; the list names it
     * too, or a screen declaring it would take the missing field for a mistake.
     */
    @Test
    void aRelationOntoAModelTheCallerCanReadNothingOfIsListedToo() {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields()).thenReturn(Map.of("BankAccount", Set.of("accountNumber")));

        List<MetaField> employee = List.of(field("bankAccountId", FieldType.ONE_TO_ONE, "BankAccount"));
        List<MetaField> bankAccount = List.of(
                field("id", FieldType.LONG, null),
                field("employeeId", FieldType.MANY_TO_ONE, "Employee"),
                field("accountNumber", FieldType.STRING, null));

        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            models.when(ModelManager::getModelNames).thenReturn(Set.of("Employee", "BankAccount"));
            models.when(() -> ModelManager.getModelFields("Employee")).thenReturn(employee);
            models.when(() -> ModelManager.getModelFields("BankAccount")).thenReturn(bankAccount);

            UiContext ui = uiContext(permissionService);

            assertThat(ui.getModelHiddenFieldsMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "BankAccount", Set.of("accountNumber"),
                    "Employee", Set.of("bankAccountId")));
        }
    }

    @Test
    void withoutAPermissionEngineNothingIsHidden() {
        assertThat(uiContext(null).getModelHiddenFieldsMap()).isEmpty();
    }

    private static MetaField field(String name, FieldType type, String relatedModel) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(relatedModel);
        return field;
    }
}
