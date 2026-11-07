package io.softa.starter.file.excel.export.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.MetaOptionItem;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.meta.OptionManager;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.enums.ImportRule;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The half of the resolver that decides which set a column belongs to.
 *
 * <p>A relation onto {@code TenantOptionItem} names its option set in the field's own filters, and the
 * dropdown is only correct if that name is read back out. Read the wrong thing and the column offers
 * another set's codes — a list that looks plausible and imports as an unresolvable value.
 *
 * <p>The routing — which of the four shapes a column is, and what each one asks for — is exercised
 * against a stubbed metadata snapshot. What it decides is visible in the query it issues, so the
 * assertions are on that: which model, which field, and what it is narrowed by.
 */
class OptionDropdownResolverTest {

    private String optionSetCodeIn(Filters filters) throws Exception {
        Method method = OptionDropdownResolver.class
                .getDeclaredMethod("optionSetCodeIn", Filters.class);
        method.setAccessible(true);
        return (String) method.invoke(new OptionDropdownResolver(), filters);
    }

    @Test
    void readsTheSetOutOfASingleEqualityFilter() throws Exception {
        // The shape every such field declares today: ["optionSetCode", "=", "OrganizationType"].
        assertThat(optionSetCodeIn(Filters.of("optionSetCode", Operator.EQUAL, "OrganizationType")))
                .isEqualTo("OrganizationType");
    }

    @Test
    void findsItAlongsideAnotherCondition() throws Exception {
        // A filter is a tree. Reading a fixed position works until a second condition is added and
        // pushes the one that matters out of place, so the search has to walk it.
        Filters filters = Filters.of("activeFlag", Operator.EQUAL, true)
                .and(Filters.of("optionSetCode", Operator.EQUAL, "ProjectType"));

        assertThat(optionSetCodeIn(filters)).isEqualTo("ProjectType");
    }

    @Test
    void answersNullWhenNoConditionNamesASet() throws Exception {
        // Nothing to narrow by means the list would be every option item the tenant owns, across every
        // set — so the column gets no dropdown rather than a misleading one.
        assertThat(optionSetCodeIn(Filters.of("activeFlag", Operator.EQUAL, true))).isNull();
        assertThat(optionSetCodeIn(null)).isNull();
        assertThat(optionSetCodeIn(new Filters())).isNull();
    }

    // ---------------------------------------------------------------- routing

    @Test
    void aBooleanColumnOffersTheLabelsRatherThanTrueAndFalse() {
        // Yes / No is what the export writes and what a person reading the sheet expects; the import
        // handler takes either form. Read from the platform's own boolean set so the two sides cannot
        // drift apart.
        withMetadata(mm -> {
            field("JobGrade", "active", FieldType.BOOLEAN, null, null, null);
            optionSet(BaseConstant.BOOLEAN_OPTION_SET_CODE, List.of(item("true", "Yes"), item("false", "No")));

            assertThat(resolve("JobGrade", null, "active")).containsEntry(0, List.of("Yes", "No"));
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void reachesThroughAOneToOneIntoAnEnum() {
        // `employeeProfileId.gender` is two hops but one dotted level: the root is a sub-record, the
        // leaf an option field on it. Not following it was why every template built on a one-to-one
        // carried no dropdowns at all.
        withMetadata(mm -> {
            field("Employee", "employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null, null);
            field("EmployeeProfile", "gender", FieldType.OPTION, null, "Gender", null);
            optionSet("Gender", List.of(item("male", "Male"), item("female", "Female")));

            // Labels, not item codes: the templates all ask for the name, and `male` next to
            // `female` is not what the reader is choosing between. The import handler takes either.
            assertThat(resolve("Employee", null, "employeeProfileId.gender"))
                    .containsEntry(0, List.of("Male", "Female"));
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void aPlainFieldInsideTheRowsOwnSubRecordIsNotAValueSet() {
        // `employeeProfileId.personalEmail` is not a list to pick from. A one-to-one target holds one
        // row per parent row, so the "values" of a field on it are simply what other people happen to
        // have — and offering them writes those people's data into a file anyone who can download a
        // template receives. On the employee template that reached personal email, personal phone, and
        // the ID number.
        //
        // It is also useless as a dropdown: nobody picks their own email off a list of other people's.
        withMetadata(mm -> {
            field("Employee", "employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null, null);
            field("EmployeeProfile", "personalEmail", FieldType.STRING, null, null, null);
            model("EmployeeProfile", false);

            assertThat(resolve("Employee", null, "employeeProfileId.personalEmail")).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void butAOneToOneStillReachesTheThingsThatAreValueSets() {
        // The rule is about the shape of the target, not about one-to-ones. An option field on the
        // sub-record still answers from metadata, and a relation on it still offers its code-as-ids —
        // both are sets that exist independently of any employee.
        withMetadata(mm -> {
            field("Employee", "employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null, null);
            field("EmployeeProfile", "gender", FieldType.OPTION, null, "Gender", null);
            optionSet("Gender", List.of(item("male", "Male")));

            assertThat(resolve("Employee", null, "employeeProfileId.gender"))
                    .containsEntry(0, List.of("Male"));
        });
    }

    @Test
    void aColumnTheTemplateDeclaresFreeTextGetsNoDropdown() {
        // Which columns offer a list is a product decision, written per column in the specification
        // ("非下拉值，填写员工code"). Everything else here infers it from metadata, and metadata cannot
        // tell a dictionary from a data table — Bank and Employee are both a many-to-one carrying a
        // name. So the templates pointing at people and departments offered the whole staff list, in
        // the columns whose specification says to type the code.
        //
        // Inference stays the default; this is the way to overrule it where the spec has spoken.
        withMetadata(mm -> {
            field("EmpAddress", "employeeId", FieldType.MANY_TO_ONE, "Employee", null, null);
            field("Employee", "fullName", FieldType.STRING, null, null, null);
            model("Employee", false);

            assertThat(resolveDeclared("EmpAddress", "employeeId.fullName", true)).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void andWithoutThatDeclarationTheSameColumnStillInfers() {
        // The flag is opt-out, not a new requirement: everything that is not marked keeps working the
        // way it did, or every dropdown in every other template would go dark at once.
        withMetadata(mm -> {
            field("Employee", "bankId", FieldType.MANY_TO_ONE, "Bank", null, null);
            field("Bank", "name", FieldType.STRING, null, null, null);
            model("Bank", false);
            stubRows("Bank", "name", List.of("DBS BANK LTD"));

            assertThat(resolveDeclared("Employee", "bankId.name", false))
                    .containsEntry(0, List.of("DBS BANK LTD"));
        });
    }

    @Test
    void aRelationColumnAsksTheTargetModelForTheFieldTheColumnNames() {
        // `bankId.name` offers Bank.name — the very value RelationLookupResolver reverse-looks-up on
        // the way back in, so a sheet filled from its own dropdown imports without translation.
        withMetadata(mm -> {
            field("Employee", "bankId", FieldType.MANY_TO_ONE, "Bank", null, null);
            field("Bank", "name", FieldType.STRING, null, null, null);
            model("Bank", false);
            stubRows("Bank", "name", List.of("DBS BANK LTD", "OCBC"));

            assertThat(resolve("Employee", null, "bankId.name"))
                    .containsEntry(0, List.of("DBS BANK LTD", "OCBC"));
            assertThat(capturedQuery("Bank").getFields()).containsExactly("name");
            assertThat(capturedQuery("Bank").isDistinct()).isTrue();
        });
    }

    @Test
    void narrowsACountryScopedModelByTheTemplatesOwnCountry() {
        // The template declares the country it is for. An administrator of an SG company downloading
        // the NZ template must get NZ values, so the country cannot come from the request context.
        withMetadata(mm -> {
            field("Employee", "passTypeId", FieldType.MANY_TO_ONE, "PassType", null, null);
            field("PassType", "name", FieldType.STRING, null, null, null);
            model("PassType", true);
            fieldExists("PassType", "country");
            stubRows("PassType", "name", List.of("Work Permit"));

            resolve("Employee", "NZ", "passTypeId.name");

            assertThat(capturedQuery("PassType").getFilters())
                    .hasToString("[\"country\",\"=\",\"NZ\"]");
        });
    }

    @Test
    void leavesAModelThatDoesNotVaryByCountryUnnarrowed() {
        // Filtering on a column the model does not have would fail the query, which costs the whole
        // dropdown rather than narrowing it.
        withMetadata(mm -> {
            field("Employee", "jobGradeId", FieldType.MANY_TO_ONE, "JobGrade", null, null);
            field("JobGrade", "name", FieldType.STRING, null, null, null);
            model("JobGrade", false);
            stubRows("JobGrade", "name", List.of("G1"));

            resolve("Employee", "SG", "jobGradeId.name");

            assertThat(Filters.isEmpty(capturedQuery("JobGrade").getFilters())).isTrue();
        });
    }

    @Test
    void twoColumnsWantingTheSameValuesShareOneQuery() {
        // A template picks a project team twice — default and additional. Asking twice would double
        // the cost of every such pair for no difference in the answer.
        withMetadata(mm -> {
            field("Employee", "projectTeamId", FieldType.MANY_TO_ONE, "ProjectTeam", null, null);
            field("Employee", "additionalProjectTeamIds", FieldType.MANY_TO_MANY, "ProjectTeam", null, null);
            field("ProjectTeam", "name", FieldType.STRING, null, null, null);
            model("ProjectTeam", false);
            stubRows("ProjectTeam", "name", List.of("Alpha"));

            Map<Integer, List<String>> resolved =
                    resolve("Employee", null, "projectTeamId.name", "additionalProjectTeamIds.name");

            assertThat(resolved).containsEntry(0, List.of("Alpha")).containsEntry(1, List.of("Alpha"));
            verify(modelService, times(1)).searchList(eq("ProjectTeam"), any(FlexQuery.class));
        });
    }

    @Test
    void aBareRelationColumnGetsNoDropdown() {
        // Addressed without a dotted path the cell holds a raw foreign key. A list of ids is not
        // something anyone can pick from, and a list of labels would look right and fail on import.
        withMetadata(mm -> {
            field("Employee", "bankId", FieldType.MANY_TO_ONE, "Bank", null, null);

            assertThat(resolve("Employee", null, "bankId")).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void ignoresAPathWithASecondDot() {
        // The import side rejects a two-level cascade outright, so a dropdown there would be values
        // for a column that cannot import.
        withMetadata(mm -> {
            field("Employee", "deptId", FieldType.MANY_TO_ONE, "Department", null, null);

            assertThat(resolve("Employee", null, "deptId.companyId.code")).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void offersTheIdsOfACodeAsIdRelation() {
        // `IdType` carries its code as its id (SG_NRIC), so the foreign key in the cell already is the
        // value a person would pick. Nothing has to be translated on the way back in either.
        withMetadata(mm -> {
            field("EmployeeProfile", "idType", FieldType.MANY_TO_ONE, "IdType", null, null);
            model("IdType", false, IdStrategy.EXTERNAL_ID);
            stubRows("IdType", "id", List.of("SG_NRIC", "SG_FIN"));

            assertThat(resolve("EmployeeProfile", null, "idType"))
                    .containsEntry(0, List.of("SG_NRIC", "SG_FIN"));
            assertThat(capturedQuery("IdType").getFields()).containsExactly("id");
        });
    }

    @Test
    void reachesThroughAOneToOneOntoACodeAsIdRelation() {
        // The employee template addresses most of the profile's country data this way. The leaf is a
        // relation, so nothing can be read *through* it — but the key it holds is the code.
        withMetadata(mm -> {
            field("Employee", "employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null, null);
            field("EmployeeProfile", "passType", FieldType.MANY_TO_ONE, "PassType", null, null);
            model("PassType", true, IdStrategy.EXTERNAL_ID);
            fieldExists("PassType", "country");
            stubRows("PassType", "id", List.of("SG_EP"));

            assertThat(resolve("Employee", "SG", "employeeProfileId.passType"))
                    .containsEntry(0, List.of("SG_EP"));
            assertThat(capturedQuery("PassType").getFilters()).hasToString("[\"country\",\"=\",\"SG\"]");
        });
    }

    @Test
    void stillOffersNothingForARelationWhoseIdIsGenerated() {
        // A bank id is a distributed long. A list of those is not something anyone can pick from, and
        // a list of names would look right and fail on import — such a column has to be addressed as
        // `bankId.name` instead.
        withMetadata(mm -> {
            field("Employee", "bankId", FieldType.MANY_TO_ONE, "Bank", null, null);
            model("Bank", false, IdStrategy.DISTRIBUTED_LONG);

            assertThat(resolve("Employee", null, "bankId")).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void doesNotOfferIdsForASubRecordOrAChildCollection() {
        // A one-to-one is the row's own sub-record and a one-to-many its children. Neither is chosen
        // from a list, whatever the target's id strategy happens to be.
        withMetadata(mm -> {
            field("Employee", "employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null, null);
            field("Employee", "addressIds", FieldType.ONE_TO_MANY, "EmployeeAddress", null, null);
            model("EmployeeProfile", false, IdStrategy.EXTERNAL_ID);
            model("EmployeeAddress", false, IdStrategy.EXTERNAL_ID);

            assertThat(resolve("Employee", null, "employeeProfileId", "addressIds")).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    @Test
    void reachesThroughAOneToOneOntoARelationAddressedByName() {
        // employeeProfileId.idType.name — the import side now resolves the name back to the id, so
        // the dropdown offers names. Narrowed by the template's country like any entity column:
        // "Passport" names a different row in every country that has one.
        withMetadata(mm -> {
            field("Employee", "employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null, null);
            field("EmployeeProfile", "idType", FieldType.MANY_TO_ONE, "IdType", null, null);
            fieldExists("IdType", "name");
            field("IdType", "name", FieldType.STRING, null, null, null);
            model("IdType", true, IdStrategy.EXTERNAL_ID);
            fieldExists("IdType", "country");
            stubRows("IdType", "name", List.of("NRIC", "FIN", "Passport"));

            assertThat(resolve("Employee", "SG", "employeeProfileId.idType.name"))
                    .containsEntry(0, List.of("NRIC", "FIN", "Passport"));
            assertThat(capturedQuery("IdType").getFilters()).hasToString("[\"country\",\"=\",\"SG\"]");
        });
    }

    @Test
    void offersNothingForThreeSegmentsThroughAManyToOne() {
        // The import side rejects this shape outright, so a dropdown would offer values for a column
        // that can never be imported.
        withMetadata(mm -> {
            field("Employee", "deptId", FieldType.MANY_TO_ONE, "Department", null, null);
            field("Department", "companyId", FieldType.MANY_TO_ONE, "LegalEntity", null, null);
            fieldExists("LegalEntity", "name");

            assertThat(resolve("Employee", null, "deptId.companyId.name")).isEmpty();
            verifyNoInteractions(modelService);
        });
    }

    // ---------------------------------------------------------------- columns stay independent

    @Test
    void relatedColumnsEachKeepTheirWholeList() {
        // A department sheet carries its parent department and its person in charge, and the two
        // models point at each other: a department names its head, an employee names their department.
        // That is an association, not a hierarchy, and nothing in the metadata tells the two apart —
        // so no column is narrowed by another. Pairing them by the relation alone left the parent
        // column empty until a head was picked.
        withMetadata(mm -> {
            field("Department", "parentId", FieldType.MANY_TO_ONE, "Department", null, null);
            field("Department", "picEmpId", FieldType.MANY_TO_ONE, "Employee", null, null);
            field("Department", "name", FieldType.STRING, null, null, null);
            field("Employee", "departmentId", FieldType.MANY_TO_ONE, "Department", null, null);
            field("Employee", "name", FieldType.STRING, null, null, null);
            model("Department", false);
            model("Employee", false);
            stubRows("Department", "name", List.of("Finance", "HR"));
            stubRows("Employee", "name", List.of("Ada", "Grace"));

            assertThat(resolve("Department", null, "parentId.name", "picEmpId.name"))
                    .containsEntry(0, List.of("Finance", "HR"))
                    .containsEntry(1, List.of("Ada", "Grace"));
            assertThat(capturedQuery("Department").getFields()).containsExactly("name");
            assertThat(capturedQuery("Employee").getFields()).containsExactly("name");
        });
    }

    // ---------------------------------------------------------------- harness

    // ---------------------------------------------------------------- the company grant

    @Test
    void aColumnOntoTheCompanyOffersOnlyTheCompaniesTheWriteIsGranted() {
        // Read as any other relation, the company list reaches the companies of every role the user
        // holds, and the self-service role carries no company grant, so it reaches them all. An HR
        // granted one company was offered every signing entity, and the import took the one they picked.
        withMetadata(mm -> {
            field("Employee", "legalEntityId", FieldType.MANY_TO_ONE, "Company", null, null);
            field("Company", "name", FieldType.STRING, null, null, null);
            model("Company", false);
            stubRows("Company", "name", List.of("Albedo"));
            when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of(7L));

            assertThat(resolve("Employee", null, ImportRule.ONLY_CREATE, "legalEntityId.name"))
                    .containsEntry(0, List.of("Albedo"));
            assertThat(capturedQuery("Company").getFilters())
                    .hasToString("[\"id\",\"IN\",[7]]");
        });
    }

    @Test
    void aCreateOrUpdateTemplateOffersTheCompaniesEitherWriteIsGranted() {
        // A row may create or update, so the column offers what either may name. The write itself still
        // refuses a company outside the grant of the action it performs.
        withMetadata(mm -> {
            field("Employee", "legalEntityId", FieldType.MANY_TO_ONE, "Company", null, null);
            field("Company", "name", FieldType.STRING, null, null, null);
            model("Company", false);
            stubRows("Company", "name", List.of("Albedo", "Zingkey"));
            when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of(7L));
            when(permissionService.getGrantedCompanyIds("Employee", AccessType.UPDATE)).thenReturn(Set.of(8L));

            resolve("Employee", null, ImportRule.CREATE_OR_UPDATE, "legalEntityId.name");

            assertThat(capturedQuery("Company").getFilters())
                    .hasToString("[\"id\",\"IN\",[7,8]]");
        });
    }

    @Test
    void anUnrestrictedWriteLeavesTheCompanyColumnWhole() {
        // An administrator, or a holding role with no company grant: nothing to narrow by.
        withMetadata(mm -> {
            field("Employee", "legalEntityId", FieldType.MANY_TO_ONE, "Company", null, null);
            field("Company", "name", FieldType.STRING, null, null, null);
            model("Company", false);
            stubRows("Company", "name", List.of("Albedo", "Zingkey"));
            when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(null);

            assertThat(resolve("Employee", null, ImportRule.ONLY_CREATE, "legalEntityId.name"))
                    .containsEntry(0, List.of("Albedo", "Zingkey"));
            assertThat(Filters.isEmpty(capturedQuery("Company").getFilters())).isTrue();
        });
    }

    @Test
    void aWriteGrantedNoCompanyOffersNoCompany() {
        // Every company the column could list would be refused by the write, so it lists none.
        withMetadata(mm -> {
            field("Employee", "legalEntityId", FieldType.MANY_TO_ONE, "Company", null, null);
            field("Company", "name", FieldType.STRING, null, null, null);
            model("Company", false);
            stubRows("Company", "name", List.of("Albedo", "Zingkey"));
            when(permissionService.getGrantedCompanyIds("Employee", AccessType.UPDATE)).thenReturn(Set.of());

            assertThat(resolve("Employee", null, ImportRule.ONLY_UPDATE, "legalEntityId.name"))
                    .doesNotContainKey(0);
            assertThat(queriesByModel).doesNotContainKey("Company");
        });
    }

    @Test
    void aTemplateWithoutACompanyColumnDoesNotAskForTheGrant() {
        withMetadata(mm -> {
            field("Employee", "bankId", FieldType.MANY_TO_ONE, "Bank", null, null);
            field("Bank", "name", FieldType.STRING, null, null, null);
            model("Bank", false);
            stubRows("Bank", "name", List.of("DBS BANK LTD"));

            resolve("Employee", null, "bankId.name");

            verifyNoInteractions(permissionService);
        });
    }

    private final ModelService<?> modelService = mock(ModelService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final Map<String, MetaField> fields = new LinkedHashMap<>();
    private final Map<String, MetaModel> models = new LinkedHashMap<>();
    private final Map<String, List<MetaOptionItem>> optionSets = new LinkedHashMap<>();
    private final List<String> extraFields = new ArrayList<>();
    private final Map<String, FlexQuery> queriesByModel = new LinkedHashMap<>();

    /**
     * Runs the body with {@link ModelManager} and {@link OptionManager} answering from the maps above.
     * Both are static gateways onto a snapshot that only exists in a running application.
     */
    private void withMetadata(java.util.function.Consumer<MockedStatic<ModelManager>> body) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<OptionManager> om = Mockito.mockStatic(OptionManager.class)) {
            mm.when(() -> ModelManager.getModelFieldOrNull(any(), any()))
                    .thenAnswer(inv -> fields.get(inv.getArgument(0) + "." + inv.getArgument(1)));
            mm.when(() -> ModelManager.existModel(any())).thenAnswer(inv -> models.containsKey(inv.getArgument(0)));
            mm.when(() -> ModelManager.getModel(any())).thenAnswer(inv -> models.get(inv.getArgument(0)));
            mm.when(() -> ModelManager.getIdStrategy(any())).thenAnswer(inv -> {
                MetaModel metaModel = models.get(inv.getArgument(0));
                return metaModel == null ? null : metaModel.getIdStrategy();
            });
            mm.when(() -> ModelManager.getModelFields(any())).thenAnswer(inv -> fields.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(inv.getArgument(0) + "."))
                    .map(Map.Entry::getValue)
                    .toList());
            mm.when(() -> ModelManager.existField(any(), any()))
                    .thenAnswer(inv -> extraFields.contains(inv.getArgument(0) + "." + inv.getArgument(1))
                            || fields.containsKey(inv.getArgument(0) + "." + inv.getArgument(1)));
            om.when(() -> OptionManager.existsOptionSetCode(any()))
                    .thenAnswer(inv -> optionSets.containsKey(inv.getArgument(0)));
            om.when(() -> OptionManager.getMetaOptionItems(any()))
                    .thenAnswer(inv -> optionSets.getOrDefault(inv.getArgument(0), List.of()));
            body.accept(mm);
        }
    }

    /** Resolve one column, saying whether the template declared it free text. */
    private Map<Integer, List<String>> resolveDeclared(String modelName, String fieldName, boolean noDropdown) {
        OptionDropdownResolver resolver = new OptionDropdownResolver();
        ReflectionTestUtils.setField(resolver, "modelService", modelService);
        ReflectionTestUtils.setField(resolver, "permissionService", permissionService);
        ImportFieldDTO field = new ImportFieldDTO();
        field.setFieldName(fieldName);
        field.setNoDropdown(noDropdown);
        return resolver.resolve(modelName, List.of(field), null, ImportRule.CREATE_OR_UPDATE);
    }

    private Map<Integer, List<String>> resolve(String modelName, String country, String... columns) {
        return resolve(modelName, country, ImportRule.CREATE_OR_UPDATE, columns);
    }

    private Map<Integer, List<String>> resolve(String modelName, String country, ImportRule importRule,
                                               String... columns) {
        OptionDropdownResolver resolver = new OptionDropdownResolver();
        ReflectionTestUtils.setField(resolver, "modelService", modelService);
        ReflectionTestUtils.setField(resolver, "permissionService", permissionService);
        List<ImportFieldDTO> importFields = new ArrayList<>();
        for (String column : columns) {
            ImportFieldDTO dto = new ImportFieldDTO();
            dto.setFieldName(column);
            importFields.add(dto);
        }
        return resolver.resolve(modelName, importFields, country, importRule);
    }

    /**
     * A metadata field, populated reflectively.
     *
     * <p>{@link MetaField}'s setters are package-private on purpose — the snapshot is built at startup
     * and not meant to be written from elsewhere. Widening them so a test can call them would trade a
     * real constraint for a convenience.
     */
    private void field(String modelName, String fieldName, FieldType fieldType,
                       String relatedModel, String optionSetCode, String filters) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", modelName);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        ReflectionTestUtils.setField(metaField, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(metaField, "optionSetCode", optionSetCode);
        ReflectionTestUtils.setField(metaField, "filters", filters);
        fields.put(modelName + "." + fieldName, metaField);
    }

    private void model(String modelName, boolean multiCountry) {
        model(modelName, multiCountry, IdStrategy.DISTRIBUTED_LONG);
    }

    private void model(String modelName, boolean multiCountry, IdStrategy idStrategy) {
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", modelName);
        ReflectionTestUtils.setField(metaModel, "multiCountry", multiCountry);
        ReflectionTestUtils.setField(metaModel, "idStrategy", idStrategy);
        models.put(modelName, metaModel);
    }

    private void fieldExists(String modelName, String fieldName) {
        extraFields.add(modelName + "." + fieldName);
    }

    private void optionSet(String optionSetCode, List<MetaOptionItem> items) {
        optionSets.put(optionSetCode, items);
    }

    private static MetaOptionItem item(String itemCode, String label) {
        MetaOptionItem optionItem = new MetaOptionItem();
        ReflectionTestUtils.setField(optionItem, "itemCode", itemCode);
        ReflectionTestUtils.setField(optionItem, "label", label);
        return optionItem;
    }

    @SuppressWarnings("unchecked")
    private void stubRows(String modelName, String fieldName, List<String> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        values.forEach(value -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(fieldName, value);
            rows.add(row);
        });
        when(((ModelService<Long>) modelService).searchList(eq(modelName), any(FlexQuery.class)))
                .thenAnswer(inv -> {
                    queriesByModel.put(inv.getArgument(0), inv.getArgument(1));
                    return rows;
                });
    }

    /** The query the resolver actually issued for a model — what its routing decision is visible as. */
    private FlexQuery capturedQuery(String modelName) {
        FlexQuery flexQuery = queriesByModel.get(modelName);
        assertThat(flexQuery).as("no query was issued for %s", modelName).isNotNull();
        return flexQuery;
    }
}
