package io.softa.framework.orm.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/**
 * Which field of a related model an import column naming a to-one relation is matched by. Its first
 * answer is the column's default, so it has to be one that names a single row: the name a person
 * writes when that name is unique, the business key otherwise, nothing when no single field will do.
 */
class LookupKeysTest {

    private final Map<String, MetaModel> models = new java.util.HashMap<>();
    private final Map<String, List<MetaIndex>> indexes = new java.util.HashMap<>();

    private void model(String name, List<String> businessKey, List<String> displayName) {
        MetaModel model = new MetaModel();
        model.setModelName(name);
        model.setMultiTenant(true);
        ReflectionTestUtils.setField(model, "businessKey", businessKey);
        ReflectionTestUtils.setField(model, "displayName", displayName);
        models.put(name, model);
    }

    private void uniqueIndex(String model, String... fields) {
        MetaIndex index = new MetaIndex();
        index.setModelName(model);
        index.setIndexName("uk_" + model + "_" + String.join("_", fields));
        index.setUniqueIndex(true);
        index.setIndexFields(List.of(fields));
        indexes.computeIfAbsent(model, k -> new ArrayList<>()).add(index);
    }

    private static MetaField relationTo(String relatedModel, FieldType idType) {
        MetaField field = new MetaField();
        field.setFieldName("relation");
        field.setFieldType(FieldType.MANY_TO_ONE);
        field.setRelatedModel(relatedModel);
        field.setRelatedFieldType(idType);
        return field;
    }

    private List<String> lookupKeys(MetaField relation) {
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> models.containsKey(inv.<String>getArgument(0)));
            mm.when(() -> ModelManager.getModel(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> models.get(inv.<String>getArgument(0)));
            mm.when(() -> ModelManager.getModelIndexes(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> indexes.getOrDefault(inv.<String>getArgument(0), List.of()));
            return LookupKeys.of(relation);
        }
    }

    @Test
    void aUniqueNameIsRecommendedAheadOfTheCode() {
        // IPA Position: keyed by code, but its name is unique in the tenant and is what a sheet carries.
        model("IpaPosition", List.of("code"), List.of("name"));
        uniqueIndex("IpaPosition", "tenantId", "code");
        uniqueIndex("IpaPosition", "tenantId", "name");

        assertThat(lookupKeys(relationTo("IpaPosition", FieldType.LONG))).containsExactly("name", "code");
    }

    @Test
    void aNameUniqueOnlyUnderAParentFallsBackToTheCode() {
        // A department name repeats across parents, so a name names several rows in the tenant.
        model("Department", List.of("code"), List.of("name"));
        uniqueIndex("Department", "tenantId", "code");
        uniqueIndex("Department", "tenantId", "parentId", "name");

        assertThat(lookupKeys(relationTo("Department", FieldType.LONG))).containsExactly("code");
    }

    @Test
    void aPersonIsLookedUpByCodeSinceNamesRepeat() {
        model("Employee", List.of("code"), List.of("fullName", "code"));
        uniqueIndex("Employee", "tenantId", "code");

        assertThat(lookupKeys(relationTo("Employee", FieldType.LONG))).containsExactly("code");
    }

    @Test
    void aModelWithoutABusinessKeyStillOffersItsUniqueFields() {
        model("JobLevel", List.of(), List.of("name"));
        uniqueIndex("JobLevel", "tenantId", "code");
        uniqueIndex("JobLevel", "tenantId", "name");

        assertThat(lookupKeys(relationTo("JobLevel", FieldType.LONG))).containsExactly("name", "code");
    }

    @Test
    void aCompositeKeyOffersNothing() {
        // No single column identifies an employment type; recommending its first component (the country)
        // would match every type of that country.
        model("EmploymentType", List.of("country", "code"), List.of("name"));
        uniqueIndex("EmploymentType", "country", "code");

        assertThat(lookupKeys(relationTo("EmploymentType", FieldType.LONG))).isEmpty();
    }

    @Test
    void aCodeAsIdMasterIsLookedUpByItsIdAlready() {
        model("CountryRegion", List.of(), List.of("name"));
        ReflectionTestUtils.setField(models.get("CountryRegion"), "idStrategy", IdStrategy.EXTERNAL_ID);
        uniqueIndex("CountryRegion", "name");

        assertThat(lookupKeys(relationTo("CountryRegion", FieldType.STRING))).isEmpty();
    }

    @Test
    void aGeneratedStringIdIsNoCodeAndStillGetsARecommendation() {
        model("FlowDesign", List.of("code"), List.of("name"));
        ReflectionTestUtils.setField(models.get("FlowDesign"), "idStrategy", IdStrategy.DISTRIBUTED_STRING);
        uniqueIndex("FlowDesign", "code");

        assertThat(lookupKeys(relationTo("FlowDesign", FieldType.STRING))).containsExactly("code");
    }

    @Test
    void aKeyComponentTheRelationPinsIsDropped() {
        // Department.orgType points at tenant option items, keyed {optionSetCode, itemCode}, and pins the
        // option set in its filters: the item code alone is what is left to fill.
        model("TenantOptionItem", List.of("optionSetCode", "itemCode"), List.of("itemCode", "label"));
        MetaField orgType = relationTo("TenantOptionItem", FieldType.LONG);
        orgType.setFilters("[\"optionSetCode\", \"=\", \"OrganizationType\"]");

        assertThat(lookupKeys(orgType)).containsExactly("itemCode");
    }
}
