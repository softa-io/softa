package io.softa.starter.user.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.dto.WizardSaveDTO;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.service.DynamicRoleSyncJob;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.SystemRoleWriteGuard;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one thing an administrator cannot express in this payload: the difference between "this role may
 * see every company" and "I forgot to say which companies".
 *
 * <p>Both are the absence of a company scope, and absence has to keep meaning unrestricted — a role
 * nobody configured must go on working, or shipping the axis would blank every existing screen at once.
 * So the ambiguity is resolved by demanding an answer at save time, from the only party who has one.
 * Nothing downstream can: by the time the snapshot is built, the two are the same data.
 *
 * <p>What makes these worth pinning is the near miss on the other side. Requiring a company scope from
 * every role that merely touches a company-scoped model is the obvious rule and the wrong one: a
 * self-service employee role touches several, and must stay configurable with no company scope at all —
 * its row scope is {@code SELF}, which ANDs down to the caller's own record, so an unrestricted company
 * axis widens nothing. That rule would block the commonest role in the system while catching nothing it
 * needed to.
 */
class RoleCompanyScopeValidationTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String COMPANY_SCOPED_MODEL = "Department";
    private static final String PLAIN_MODEL = "OptionSetLike";

    private MockedStatic<ModelManager> modelManager;
    private RoleDataScopeService roleDataScopeService;
    private RoleController controller;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        declare(COMPANY_SCOPED_MODEL, true, "Department");
        declare(PLAIN_MODEL, false, "Option Set");
        declare(ModelConstant.COMPANY_MODEL, false, "Legal Entity");

        RoleService roleService = mock(RoleService.class);
        when(roleService.createOne(any(Role.class))).thenReturn(42L);
        roleDataScopeService = mock(RoleDataScopeService.class);
        ModelService modelService = mock(ModelService.class);
        controller = new RoleController(
                roleService, mock(RoleNavigationService.class),
                roleDataScopeService, mock(RoleSensitiveFieldSetService.class),
                mock(UserRoleRelService.class), mock(DynamicRoleSyncJob.class),
                mock(io.softa.starter.user.service.impl.UiContextBuilder.class),
                mock(io.softa.starter.user.service.RoleGrantCeiling.class));
        // Injected by Spring in production; assigned directly here (same package) now that the write
        // verbs and their SystemRoleWriteGuard call live in SystemRoleGuardedController.
        controller.modelService = castModelService(modelService);
        controller.writeGuard = mock(SystemRoleWriteGuard.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private void declare(String model, boolean multiCompany, String label) {
        MetaModel meta = mock(MetaModel.class);
        when(meta.isMultiCompany()).thenReturn(multiCompany);
        when(meta.getLabel()).thenReturn(label);
        modelManager.when(() -> ModelManager.existModel(model)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModel(model)).thenReturn(meta);
    }

    /** One {model, dataScopes:[{scopeType}, …]} entry; a null type list omits dataScopes entirely. */
    private static ObjectNode scopeRow(String model, String... scopeTypes) {
        ObjectNode row = JSON.objectNode();
        row.put("model", model);
        if (scopeTypes != null) {
            ArrayNode rules = row.putArray("dataScopes");
            for (String type : scopeTypes) {
                rules.addObject().put("scopeType", type);
            }
        }
        return row;
    }

    private static WizardSaveDTO wizardWith(ObjectNode... rows) {
        ArrayNode scopes = JSON.arrayNode();
        for (ObjectNode row : rows) {
            scopes.add(row);
        }
        return new WizardSaveDTO(JSON.objectNode().put("name", "Regional HR"), null, scopes, null, null);
    }

    // ---- rejected ---------------------------------------------------------

    @Test
    void everyRowOfACompanyScopedModelWithNoCompanyScopeIsRejected() {
        // The regional HR who gets every region: ALL on Department, nothing said about companies. The
        // save is refused rather than silently granting the wider role, and nothing is written.
        assertThatThrownBy(() -> controller.createWithWizard(wizardWith(scopeRow(COMPANY_SCOPED_MODEL, "ALL"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Department");
        verify(roleDataScopeService, never()).createList(any());
    }

    @Test
    void aCompanyScopedModelWithNoRulesAtAllIsRejected() {
        // No rule for a model the role can reach reads exactly like ALL downstream: the snapshot finds
        // nothing to narrow with and the query runs unbounded. Treating the two differently here would
        // leave the same hole reachable by simply omitting the array.
        assertThatThrownBy(() -> controller.createWithWizard(wizardWith(scopeRow(COMPANY_SCOPED_MODEL))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.createWithWizard(
                wizardWith(scopeRow(COMPANY_SCOPED_MODEL, new String[0]))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anEmptyCompanyScopeDoesNotCountAsConfigured() {
        // A row for the company model whose rule list is empty says nothing, so it must not satisfy the
        // requirement — otherwise the check is defeated by sending the key with no content.
        ObjectNode emptyCompanyScope = scopeRow(ModelConstant.COMPANY_MODEL, new String[0]);

        assertThatThrownBy(() -> controller.createWithWizard(
                wizardWith(scopeRow(COMPANY_SCOPED_MODEL, "ALL"), emptyCompanyScope)))
                .isInstanceOf(BusinessException.class);
    }

    // ---- allowed ----------------------------------------------------------

    @Test
    void everyRowIsFineOnceTheCompaniesAreNamed() {
        assertThatCode(() -> controller.createWithWizard(wizardWith(
                scopeRow(COMPANY_SCOPED_MODEL, "ALL"),
                scopeRow(ModelConstant.COMPANY_MODEL, "CUSTOM"))))
                .doesNotThrowAnyException();
        verify(roleDataScopeService).createList(any());
    }

    @Test
    void aSelfScopedRoleNeedsNoCompanyScope() {
        // The self-service employee, and the reason this check is not "touches a company-scoped model".
        // SELF resolves to the caller's own record and composes with the company axis as AND, so leaving
        // the axis unrestricted widens nothing. Blocking this would block the commonest role there is.
        assertThatCode(() -> controller.createWithWizard(
                wizardWith(scopeRow(COMPANY_SCOPED_MODEL, "SELF"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aNarrowedRoleNeedsNoCompanyScope() {
        // Same reasoning one step out: any rule that is not ALL bounds the rows by something, so the
        // missing company axis is not what decides what the role sees.
        assertThatCode(() -> controller.createWithWizard(
                wizardWith(scopeRow(COMPANY_SCOPED_MODEL, "DEPT_SUBTREE", "DIRECT_REPORTS"))))
                .doesNotThrowAnyException();
    }

    @Test
    void everyRowOfAModelThatBelongsToNoCompanyIsFine() {
        // Shared reference and configuration data. Demanding a company scope for these would make the
        // check fire on roles that have nothing to do with the company dimension.
        assertThatCode(() -> controller.createWithWizard(wizardWith(scopeRow(PLAIN_MODEL, "ALL"))))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownModelIsLeftToTheWriteToReport() {
        // Sits in front of the write on the generic save path: an unknown model must reach the code that
        // reports it properly, not fail here as a company-scope problem.
        modelManager.when(() -> ModelManager.existModel("NoSuchModel")).thenReturn(false);

        assertThatCode(() -> controller.createWithWizard(wizardWith(scopeRow("NoSuchModel", "ALL"))))
                .doesNotThrowAnyException();
    }

    // ---- a condition narrows rules, so it needs one ----------------------

    @Test
    void aConditionWithoutAnyRuleIsRejected() {
        // Saved, it would be ignored at runtime — a condition only narrows the rules beside it — while
        // the role read as restricted to whoever looked at it.
        ObjectNode noRules = scopeRow(PLAIN_MODEL, new String[0]);
        noRules.putArray("scopeCondition").addArray().add("active").add("=").add(true);
        ObjectNode missingRules = scopeRow(PLAIN_MODEL);
        missingRules.putArray("scopeCondition").addArray().add("active").add("=").add(true);

        assertThatThrownBy(() -> controller.createWithWizard(wizardWith(noRules)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("An additional condition needs at least one scope rule.");
        assertThatThrownBy(() -> controller.createWithWizard(wizardWith(missingRules)))
                .isInstanceOf(BusinessException.class);
        verify(roleDataScopeService, never()).createList(any());
    }

    @Test
    void aConditionBesideARuleIsSaved() {
        ObjectNode narrowed = scopeRow(PLAIN_MODEL, "ALL");
        narrowed.putArray("scopeCondition").addArray().add("active").add("=").add(true);

        assertThatCode(() -> controller.createWithWizard(wizardWith(narrowed))).doesNotThrowAnyException();
        verify(roleDataScopeService).createList(any());
    }

    // ---- the update path shares the check --------------------------------

    @Test
    void theUpdatePathIsGuardedToo() {
        // Both wizard entry points funnel through the same writer, so a role cannot be widened by
        // editing it instead of creating it. The basics update has already run by then — the endpoint is
        // @Transactional, so the throw rolls it back rather than leaving the role half-saved.
        assertThatThrownBy(() -> controller.saveWizard(7L, wizardWith(scopeRow(COMPANY_SCOPED_MODEL, "ALL"))))
                .isInstanceOf(BusinessException.class);
        verify(roleDataScopeService, never()).createList(any());
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> castModelService(ModelService<?> service) {
        return (ModelService<Long>) service;
    }
}
