package io.softa.starter.file.excel.export.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportFieldDTO;
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

    // ---------------------------------------------------------------- cascade

    @Test
    void spotsThatOneColumnNarrowsAnother() {
        // A track names the level it belongs to, so the tracks worth offering are the ones for the
        // level already chosen. Both sides are code-as-id, so the track's foreign key holds exactly
        // the value the level column offers and the grouping needs no translation.
        withMetadata(mm -> {
            field("EmployeeProfile", "highestEducationLevel", FieldType.MANY_TO_ONE,
                    "HighestEducationLevel", null, null);
            field("EmployeeProfile", "highestEducationTrack", FieldType.MANY_TO_ONE,
                    "HighestEducationTrack", null, null);
            field("HighestEducationTrack", "level", FieldType.MANY_TO_ONE, "HighestEducationLevel", null, null);
            model("HighestEducationLevel", false, IdStrategy.EXTERNAL_ID);
            model("HighestEducationTrack", false, IdStrategy.EXTERNAL_ID);
            stubRows("HighestEducationLevel", "id", List.of("SG_Bachelor", "SG_Master"));
            stubGroupedRows("HighestEducationTrack",
                    List.of(Map.of("id", "SG_BEng", "level", "SG_Bachelor"),
                            Map.of("id", "SG_MEng", "level", "SG_Master")));

            var resolution = resolveAll("EmployeeProfile", null,
                    "highestEducationLevel", "highestEducationTrack");

            assertThat(resolution.cascadesByColumn()).containsOnlyKeys(1);
            var cascade = resolution.cascadesByColumn().get(1);
            assertThat(cascade.parentColumn()).isEqualTo(0);
            assertThat(cascade.valuesByParentValue())
                    .containsEntry("SG_Bachelor", List.of("SG_BEng"))
                    .containsEntry("SG_Master", List.of("SG_MEng"));
        });
    }

    @Test
    void leavesTwoUnrelatedColumnsAlone() {
        // Neither model points at the other, so there is nothing to narrow by. Reading a cascade into
        // any two code-as-id columns that happen to share a sheet would offer the wrong values with
        // full confidence.
        withMetadata(mm -> {
            field("EmployeeProfile", "idType", FieldType.MANY_TO_ONE, "IdType", null, null);
            field("EmployeeProfile", "passType", FieldType.MANY_TO_ONE, "PassType", null, null);
            // Both carry a many-to-one of their own — onto the country, as the country data models all
            // do. Neither points at the other, which is the only thing that makes a pair.
            field("IdType", "country", FieldType.MANY_TO_ONE, "CountryRegion", null, null);
            field("PassType", "country", FieldType.MANY_TO_ONE, "CountryRegion", null, null);
            model("IdType", false, IdStrategy.EXTERNAL_ID);
            model("PassType", false, IdStrategy.EXTERNAL_ID);
            stubRows("IdType", "id", List.of("SG_NRIC"));
            stubRows("PassType", "id", List.of("SG_EP"));

            assertThat(resolveAll("EmployeeProfile", null, "idType", "passType").cascadesByColumn())
                    .isEmpty();
            // And no grouping query was even attempted. Asserting only on the empty result would pass
            // just as well if a bogus pair had been formed and then produced nothing.
            assertThat(capturedQuery("IdType").getFields())
                    .as("only the flat list of ids was asked for").containsExactly("id");
            assertThat(capturedQuery("PassType").getFields()).containsExactly("id");
        });
    }

    @Test
    void cascadesBetweenTwoColumnsAddressedByName() {
        // The pairing used to require both columns to offer ids, which is what made a column unable to
        // be readable and cascaded at once. Addressed by name, the child's foreign key still holds the
        // parent's id — so the grouping has to be keyed by what the parent CELL shows instead.
        withMetadata(mm -> {
            field("EmployeeProfile", "highestEducationLevel", FieldType.MANY_TO_ONE,
                    "HighestEducationLevel", null, null);
            field("EmployeeProfile", "highestEducationTrack", FieldType.MANY_TO_ONE,
                    "HighestEducationTrack", null, null);
            field("HighestEducationLevel", "name", FieldType.STRING, null, null, null);
            field("HighestEducationTrack", "name", FieldType.STRING, null, null, null);
            field("HighestEducationTrack", "level", FieldType.MANY_TO_ONE, "HighestEducationLevel", null, null);
            model("HighestEducationLevel", false, IdStrategy.EXTERNAL_ID);
            model("HighestEducationTrack", false, IdStrategy.EXTERNAL_ID);
            stubRowsByFields("HighestEducationLevel", Map.of(
                    List.of("name"), rows("name", "Bachelor's Degree", "Doctorate (PhD)"),
                    List.of("id", "name"), List.of(
                            Map.of("id", "SG_Bachelor", "name", "Bachelor's Degree"),
                            Map.of("id", "SG_Doctorate", "name", "Doctorate (PhD)"))));
            stubGroupedRows("HighestEducationTrack",
                    List.of(Map.of("name", "BEng", "level", "SG_Bachelor"),
                            Map.of("name", "PhD", "level", "SG_Doctorate")));

            var resolution = resolveAll("EmployeeProfile", null,
                    "highestEducationLevel.name", "highestEducationTrack.name");

            var cascade = resolution.cascadesByColumn().get(1);
            assertThat(cascade).as("the name-addressed pair still cascades").isNotNull();
            assertThat(cascade.parentColumn()).isEqualTo(0);
            // Keyed by the shown name, not by SG_Bachelor — the validation formula MATCHes the cell.
            assertThat(cascade.valuesByParentValue())
                    .containsEntry("Bachelor's Degree", List.of("BEng"))
                    .containsEntry("Doctorate (PhD)", List.of("PhD"));
        });
    }

    @Test
    void theCountryAnchorIsNotAParent() {
        // A track points at its level and at its country with the same kind of field. Read the second
        // as a parent and the Nationality column starts narrowing the track column — and every other
        // country-partitioned column on the sheet with it.
        withMetadata(mm -> {
            field("EmployeeProfile", "nationality", FieldType.MANY_TO_ONE, "CountryRegion", null, null);
            field("EmployeeProfile", "highestEducationTrack", FieldType.MANY_TO_ONE,
                    "HighestEducationTrack", null, null);
            field("CountryRegion", "name", FieldType.STRING, null, null, null);
            field("HighestEducationTrack", "name", FieldType.STRING, null, null, null);
            field("HighestEducationTrack", "country", FieldType.MANY_TO_ONE, "CountryRegion", null, null);
            model("CountryRegion", false, IdStrategy.EXTERNAL_ID);
            model("HighestEducationTrack", true, IdStrategy.EXTERNAL_ID);
            fieldExists("HighestEducationTrack", "country");
            stubRows("CountryRegion", "name", List.of("Singapore"));
            stubRows("HighestEducationTrack", "name", List.of("BEng", "PhD"));

            var resolution = resolveAll("EmployeeProfile", null,
                    "nationality.name", "highestEducationTrack.name");

            assertThat(resolution.cascadesByColumn()).isEmpty();
            // And no grouping query was attempted. Asserting only on the empty result would pass just
            // as well if a bogus pair had been formed and then produced nothing.
            assertThat(capturedQuery("HighestEducationTrack").getFields())
                    .as("only the flat list of names was asked for").containsExactly("name");
        });
    }

    @Test
    void aCountryRelationOnAModelThatIsNotPartitionedStillPairs() {
        // The exclusion is about the partition axis, not about the word "country". A model that is not
        // multi-country has no partition axis, so its relation onto a country is an ordinary parent.
        withMetadata(mm -> {
            field("Employee", "nationality", FieldType.MANY_TO_ONE, "CountryRegion", null, null);
            field("Employee", "cityId", FieldType.MANY_TO_ONE, "City", null, null);
            field("CountryRegion", "name", FieldType.STRING, null, null, null);
            field("City", "name", FieldType.STRING, null, null, null);
            field("City", "country", FieldType.MANY_TO_ONE, "CountryRegion", null, null);
            model("CountryRegion", false, IdStrategy.EXTERNAL_ID);
            model("City", false, IdStrategy.EXTERNAL_ID);
            stubRowsByFields("CountryRegion", Map.of(
                    List.of("name"), rows("name", "Singapore"),
                    List.of("id", "name"), List.of(Map.of("id", "SG", "name", "Singapore"))));
            stubGroupedRows("City", List.of(Map.of("name", "Jurong", "country", "SG")));

            var resolution = resolveAll("Employee", null, "nationality.name", "cityId.name");

            assertThat(resolution.cascadesByColumn().get(1)).isNotNull();
            assertThat(resolution.cascadesByColumn().get(1).valuesByParentValue())
                    .containsEntry("Singapore", List.of("Jurong"));
        });
    }

    @Test
    void dropsChildrenWhoseParentTheColumnDoesNotOffer() {
        // The parent list is read under the same filters and country the parent column resolved under.
        // A child hanging off a parent outside that list belongs to a value the reader cannot pick, so
        // offering it under anything would be offering it under the wrong thing.
        withMetadata(mm -> {
            field("EmployeeProfile", "highestEducationLevel", FieldType.MANY_TO_ONE,
                    "HighestEducationLevel", null, null);
            field("EmployeeProfile", "highestEducationTrack", FieldType.MANY_TO_ONE,
                    "HighestEducationTrack", null, null);
            field("HighestEducationLevel", "name", FieldType.STRING, null, null, null);
            field("HighestEducationTrack", "name", FieldType.STRING, null, null, null);
            field("HighestEducationTrack", "level", FieldType.MANY_TO_ONE, "HighestEducationLevel", null, null);
            model("HighestEducationLevel", false, IdStrategy.EXTERNAL_ID);
            model("HighestEducationTrack", false, IdStrategy.EXTERNAL_ID);
            stubRowsByFields("HighestEducationLevel", Map.of(
                    List.of("name"), rows("name", "Bachelor's Degree"),
                    // NZ_Certificate is not in the parent list this column offers.
                    List.of("id", "name"), List.of(Map.of("id", "SG_Bachelor", "name", "Bachelor's Degree"))));
            stubGroupedRows("HighestEducationTrack",
                    List.of(Map.of("name", "BEng", "level", "SG_Bachelor"),
                            Map.of("name", "NZ Cert", "level", "NZ_Certificate")));

            var cascade = resolveAll("EmployeeProfile", null,
                    "highestEducationLevel.name", "highestEducationTrack.name").cascadesByColumn().get(1);

            assertThat(cascade.valuesByParentValue()).containsOnlyKeys("Bachelor's Degree");
            assertThat(cascade.valuesByParentValue().get("Bachelor's Degree")).containsExactly("BEng");
        });
    }

    @Test
    void anIdAddressedPairStillNeedsNoTranslation() {
        // The pre-existing shape, kept honest: when the parent column offers ids the link column
        // already holds exactly what the cell shows, and the second query would be wasted.
        withMetadata(mm -> {
            field("EmployeeProfile", "highestEducationLevel", FieldType.MANY_TO_ONE,
                    "HighestEducationLevel", null, null);
            field("EmployeeProfile", "highestEducationTrack", FieldType.MANY_TO_ONE,
                    "HighestEducationTrack", null, null);
            field("HighestEducationTrack", "level", FieldType.MANY_TO_ONE, "HighestEducationLevel", null, null);
            model("HighestEducationLevel", false, IdStrategy.EXTERNAL_ID);
            model("HighestEducationTrack", false, IdStrategy.EXTERNAL_ID);
            stubRows("HighestEducationLevel", "id", List.of("SG_Bachelor"));
            stubGroupedRows("HighestEducationTrack", List.of(Map.of("id", "SG_BEng", "level", "SG_Bachelor")));

            var cascade = resolveAll("EmployeeProfile", null,
                    "highestEducationLevel", "highestEducationTrack").cascadesByColumn().get(1);

            assertThat(cascade.valuesByParentValue()).containsEntry("SG_Bachelor", List.of("SG_BEng"));
            assertThat(capturedQuery("HighestEducationLevel").getFields())
                    .as("no id-to-name translation query").containsExactly("id");
        });
    }

    // ---------------------------------------------------------------- harness

    private final ModelService<?> modelService = mock(ModelService.class);
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
        ImportFieldDTO field = new ImportFieldDTO();
        field.setFieldName(fieldName);
        field.setNoDropdown(noDropdown);
        return resolver.resolveAll(modelName, List.of(field), null).optionsByColumn();
    }

    private Map<Integer, List<String>> resolve(String modelName, String country, String... columns) {
        return resolveAll(modelName, country, columns).optionsByColumn();
    }

    private OptionDropdownResolver.Resolution resolveAll(String modelName, String country, String... columns) {
        OptionDropdownResolver resolver = new OptionDropdownResolver();
        ReflectionTestUtils.setField(resolver, "modelService", modelService);
        List<ImportFieldDTO> importFields = new ArrayList<>();
        for (String column : columns) {
            ImportFieldDTO dto = new ImportFieldDTO();
            dto.setFieldName(column);
            importFields.add(dto);
        }
        return resolver.resolveAll(modelName, importFields, country);
    }

    /** Single-column rows, for a flat value list. */
    private static List<Map<String, Object>> rows(String field, String... values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String value : values) {
            out.add(Map.of(field, value));
        }
        return out;
    }

    /**
     * Answers differently per requested field list, for a model queried more than once in one resolve —
     * a name-addressed parent is read twice: its flat list, then its id-to-name pairs.
     */
    @SuppressWarnings("unchecked")
    private void stubRowsByFields(String modelName, Map<List<String>, List<Map<String, Object>>> byFields) {
        when(((ModelService<Long>) modelService).searchList(eq(modelName), any(FlexQuery.class)))
                .thenAnswer(inv -> {
                    FlexQuery flexQuery = inv.getArgument(1);
                    queriesByModel.put(inv.getArgument(0), flexQuery);
                    return byFields.getOrDefault(List.copyOf(flexQuery.getFields()), List.of());
                });
    }

    /** Rows carrying more than one column, for the grouping query a cascade issues. */
    @SuppressWarnings("unchecked")
    private void stubGroupedRows(String modelName, List<Map<String, Object>> rows) {
        when(((ModelService<Long>) modelService).searchList(eq(modelName), any(FlexQuery.class)))
                .thenAnswer(inv -> {
                    queriesByModel.put(inv.getArgument(0), inv.getArgument(1));
                    return rows;
                });
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
