package io.softa.starter.permission.validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.validation.WriteContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A role granted one company could create or import a record under another company's signing entity:
 * the entity was resolved against a company list that every role's grants add up to, the self-service
 * role's "every company" included. A company named by a field is now judged by the companies of the
 * roles that may perform the write.
 */
class CompanyReferenceGrantValidatorTest {

    private static final long ALBEDO = 1L;
    private static final long ZINGKEY = 2L;

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final CompanyReferenceGrantValidator validator =
            new CompanyReferenceGrantValidator(permissionService, modelService);
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        model("Contract", false,
                field("legalEntityId", FieldType.MANY_TO_ONE, "Company"),
                field("companyId", FieldType.MANY_TO_ONE, "Company"),
                field("departmentId", FieldType.MANY_TO_ONE, "Department"),
                dynamic(field("employerId", FieldType.MANY_TO_ONE, "Company")),
                cascaded(field("payingEntityId", FieldType.MANY_TO_ONE, "Company")));
        model("ContractReport", true, field("legalEntityId", FieldType.MANY_TO_ONE, "Company"));
        model("Department", false, field("companyId", FieldType.MANY_TO_ONE, "Company"));
        when(modelService.getDisplayNames(eq("Company"), any(Filters.class)))
                .thenReturn(Map.of(ZINGKEY, "Zingkey Pte Ltd"));
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void judgesTheStoredFieldsOntoTheCompanyButTheAxis() {
        // The company axis is bounded by row scope; a dynamic field is not stored; a cascaded one copies
        // the company of the record it follows.
        assertThat(validator.referencesOf("Contract")).containsExactly("legalEntityId");
        assertThat(validator.supports("Department")).isFalse();
        assertThat(validator.supports("ContractReport")).isFalse();
        assertThat(validator.supports("NoSuchModel")).isFalse();
    }

    @Test
    void refusesACreateNamingACompanyOutsideTheCreatorsGrant() {
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.CREATE)).thenReturn(Set.of(ALBEDO));
        WriteContext ctx = WriteContext.of("Contract", AccessType.CREATE, row(ZINGKEY), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("legalEntityId");
            assertThat(e.message()).contains("Zingkey Pte Ltd", "outside the companies your role is granted");
        });
    }

    @Test
    void acceptsACompanyInsideTheGrant() {
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.CREATE)).thenReturn(Set.of(ALBEDO));
        WriteContext ctx = WriteContext.of("Contract", AccessType.CREATE, row(Map.of("id", ALBEDO)), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).isEmpty();
    }

    @Test
    void anUnrestrictedWritePasses() {
        // An administrator, a write made without permission checks, or a holding role with no company grant.
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.CREATE)).thenReturn(null);
        WriteContext ctx = WriteContext.of("Contract", AccessType.CREATE, row(ZINGKEY), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).isEmpty();
    }

    @Test
    void aWriteGrantedNoCompanyNamesNone() {
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.CREATE)).thenReturn(Set.of());
        WriteContext ctx = WriteContext.of("Contract", AccessType.CREATE, row(ALBEDO), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).hasSize(1);
    }

    @Test
    void aRecordNamingNoCompanyAsksNothing() {
        WriteContext ctx = WriteContext.of("Contract", AccessType.CREATE, row(null), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).isEmpty();
        verify(permissionService, never()).getGrantedCompanyIds(any(), any());
    }

    @Test
    void anUpdateThatKeepsTheCompanyIsNotJudged() {
        // A record already under a company outside the grant stays editable in its other fields, even
        // when the form sends the whole record back.
        Map<String, Object> original = row(ZINGKEY);
        WriteContext untouched = WriteContext.of("Contract", AccessType.UPDATE, row(ZINGKEY), Map.of("name", "x"), original);
        WriteContext echoed = WriteContext.of("Contract", AccessType.UPDATE, row(ZINGKEY), row(String.valueOf(ZINGKEY)), original);

        validator.validateUpdate(untouched);
        validator.validateUpdate(echoed);

        assertThat(untouched.errors()).isEmpty();
        assertThat(echoed.errors()).isEmpty();
        verify(permissionService, never()).getGrantedCompanyIds(any(), any());
    }

    @Test
    void anUpdateMovingTheRecordIsJudgedByTheEditorsGrant() {
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.UPDATE)).thenReturn(Set.of(ALBEDO));
        WriteContext ctx = WriteContext.of("Contract", AccessType.UPDATE, row(ZINGKEY), row(ZINGKEY), row(ALBEDO));

        validator.validateUpdate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("legalEntityId"));
    }

    @Test
    void clearingTheCompanyIsNotJudged() {
        WriteContext ctx = WriteContext.of("Contract", AccessType.UPDATE, row(null), row(null), row(ZINGKEY));

        validator.validateUpdate(ctx);

        assertThat(ctx.errors()).isEmpty();
    }

    @Test
    void anUpdateIsJudgedByTheRolesReachingThatRow() {
        // The row's id goes along, so a role holding the write on other rows lends it no companies here.
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.UPDATE, 7L)).thenReturn(Set.of(ALBEDO));
        Map<String, Object> stored = new HashMap<>(row(ALBEDO));
        stored.put("id", 7L);
        WriteContext ctx = WriteContext.of("Contract", AccessType.UPDATE, row(ZINGKEY), row(ZINGKEY), stored);

        validator.validateUpdate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("legalEntityId"));
        verify(permissionService, never()).getGrantedCompanyIds("Contract", AccessType.UPDATE);
    }

    @Test
    void theGrantIsAskedOncePerWrite() {
        when(permissionService.getGrantedCompanyIds("Contract", AccessType.CREATE)).thenReturn(Set.of(ALBEDO));
        Map<String, Object> scratch = new HashMap<>();

        for (int i = 0; i < 3; i++) {
            validator.validateCreate(WriteContext.of("Contract", AccessType.CREATE, row(ALBEDO), row(ALBEDO), null, scratch));
        }

        verify(permissionService, times(1)).getGrantedCompanyIds("Contract", AccessType.CREATE);
    }

    private static Map<String, Object> row(Object legalEntity) {
        Map<String, Object> row = new HashMap<>();
        row.put("legalEntityId", legalEntity);
        return row;
    }

    private void model(String name, boolean projection, MetaField... fields) {
        modelManager.when(() -> ModelManager.existModel(name)).thenReturn(true);
        modelManager.when(() -> ModelManager.isProjectionModel(name)).thenReturn(projection);
        modelManager.when(() -> ModelManager.getModelFields(name)).thenReturn(List.of(fields));
    }

    private static MetaField field(String name, FieldType type, String relatedModel) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        return field;
    }

    private static MetaField dynamic(MetaField field) {
        ReflectionTestUtils.setField(field, "dynamic", true);
        return field;
    }

    private static MetaField cascaded(MetaField field) {
        ReflectionTestUtils.setField(field, "cascadedField", "departmentId.companyId");
        return field;
    }
}
