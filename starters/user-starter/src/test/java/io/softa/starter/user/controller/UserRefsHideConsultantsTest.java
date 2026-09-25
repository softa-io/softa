package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.config.SystemConfig;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.UserRosterScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The member dialogs offer the same roster the User Accounts page does — consultants excluded.
 *
 * <p>This endpoint read the UserAccount model raw while every other roster read went through
 * {@link UserRosterScope}. So consultant memberships, hidden from the page since they were
 * introduced, still turned up in Add-Members and Assign-Roles, where an administrator could grant a
 * role to somebody the tenant cannot see, edit, or deactivate.
 *
 * <p>Asserted on the filter that reaches the model rather than on the rows coming back: a mock can be
 * told to return whatever, and what actually protects the roster is the predicate going down.
 */
class UserRefsHideConsultantsTest {

    /** A multi-tenant deployment, which is where the hiding has to hold. The caller is an ordinary
     *  tenant user (no context, so not the platform super-admin), whose reads the ORM already
     *  narrows to their own tenant — so what this pins is exactly the consultant predicate. */
    @BeforeEach
    void multiTenantDeployment() {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> mockModelService() {
        return mock(ModelService.class);
    }

    private static UserAccessController controllerOver(ModelService<Long> modelService) {
        UserRosterScope scope = new UserRosterScope(mock(RoleService.class), mock(UserRoleRelService.class));
        return new UserAccessController(modelService, null, null, scope);
    }

    @Test
    void theRosterQueryCarriesTheConsultantHidingPredicate() {
        ModelService<Long> modelService = mockModelService();
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());

        controllerOver(modelService).listUserRefs();

        ArgumentCaptor<FlexQuery> sent = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq("UserAccount"), sent.capture());

        String filters = String.valueOf(sent.getValue().getFilters());
        assertThat(filters).contains("consultant");
        // Both halves: the flag is null on every membership that predates consultants, and a bare
        // equals-false would empty the dialog of the entire existing roster.
        assertThat(filters.toUpperCase()).contains("OR");
    }

    @Test
    void anEmptyRosterStillProducesAWellFormedResponse() {
        // The org-context join runs over the rows; zero rows must not reach it as a null list.
        ModelService<Long> modelService = mockModelService();
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());

        assertThat(controllerOver(modelService).listUserRefs().getData()).isEmpty();
    }

    @Test
    void rowsThatComeBackAreStillMappedThrough() {
        // Guards the other direction: a filter change that accidentally dropped every row would pass
        // the assertion above just as happily.
        ModelService<Long> modelService = mockModelService();
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 7L, "username", "ada")));

        assertThat(controllerOver(modelService).listUserRefs().getData())
                .singleElement()
                .satisfies(ref -> assertThat(ref.username()).isEqualTo("ada"));
    }
}
