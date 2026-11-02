package io.softa.starter.file.excel.imports;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.framework.web.filter.context.CompanyCountryResolver;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.domain.Filters;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationLookupResolverTest {

    @Test
    void detectLookupGroupsSupportsToOneAndToManyRootFields() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups(
                    "TestOrder", List.of(importField("deptId.code", null), importField("roleIds.code", null)));

            assertEquals(2, groups.size());
            assertFalse(groups.getFirst().toMany());
            assertTrue(groups.get(1).toMany());
        }
    }

    @Test
    void detectLookupGroupsRejectsNonRelationRootField() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            assertThrows(IllegalArgumentException.class,
                    () -> resolver.detectLookupGroups("TestOrder", List.of(importField("status.code", null))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToOneWritesBackFkId() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "deptId", "Department", List.of("code"), List.of("deptId.code"), true, false, false, null);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("deptId.code", "D001"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code")), anyCollection(), any()))
                .thenReturn(Map.of(List.of("D001"), 100L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(100L, row.get("deptId"));
        assertFalse(row.containsKey("deptId.code"));
    }

    /**
     * A cell with a stray space still names the row it names.
     *
     * <p>Spreadsheet cells carry them constantly — pasted from another sheet, typed with a trailing
     * space, copied out of a UI. The lookup matched the raw cell, so " E1000100" found nothing and the
     * row was rejected with a message that printed the value back WITH the space in it: invisible in
     * Excel, invisible in a terminal, invisible in a browser. The reader sees the code they typed,
     * sees it in the employee list, and has no way to tell the two apart.
     *
     * <p>The to-many path in this same class has trimmed all along, which is what made the difference
     * so hard to see: the same file could resolve a multi-value column and fail a single-value one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToOneTrimsTheCellBeforeMatching() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "employeeId", "Employee", List.of("code", "fullName"),
                List.of("employeeId.code", "employeeId.fullName"), true, false, false, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("employeeId.code", " E1000100");
        row.put("employeeId.fullName", "Chan Wei Lun ");

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Employee"), eq(List.of("code", "fullName")), anyCollection(), any()))
                .thenReturn(Map.of(List.of("E1000100", "Chan Wei Lun"), 900L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(900L, row.get("employeeId"));
        assertNull(row.get(FileConstant.FAILED_REASON));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsOneToOneFoldsIntoNestedValueObject() {
        // A OneToOne root owns its sub-record, so the dotted columns are its content, not a business
        // key: they are folded into a nested map the ORM cascade writes inline. Blank cells are left
        // out so "blank means keep the existing value" holds on update.
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "profileId", "Profile", List.of("nickname", "gender"),
                List.of("profileId.nickname", "profileId.gender"), true, false, true, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profileId.nickname", "Amy");
        row.put("profileId.gender", "  ");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(Map.of("nickname", "Amy"), row.get("profileId"));
        assertFalse(row.containsKey("profileId.nickname"));
        assertFalse(row.containsKey("profileId.gender"));
        // No lookup is issued for a OneToOne group — nothing to search for.
        verifyNoInteractions(getModelService(resolver));
    }

    @Test
    void resolveRowsOneToOneAllBlankStillYieldsEmptyObject() {
        // The sub-record belongs to the main row: a create must still produce one (the owning FK is
        // typically required), and an update relinks the existing sub-row without touching a field.
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "profileId", "Profile", List.of("nickname"), List.of("profileId.nickname"),
                true, false, true, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profileId.nickname", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(Map.of(), row.get("profileId"));
        assertFalse(row.containsKey("profileId.nickname"));
    }

    @Test
    void resolveRowsToOneIgnoreEmptyFalseWritesNull() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "deptId", "Department", List.of("code"), List.of("deptId.code"), false, false, false, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deptId.code", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertTrue(row.containsKey("deptId"));
        assertNull(row.get("deptId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyWritesBackIdList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code"), List.of("roleIds.code"), true, true, false, null);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("roleIds.code", "ADMIN,USER"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("code")), anyCollection(), any()))
                .thenReturn(Map.of(List.of("ADMIN"), 11L, List.of("USER"), 12L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(List.of(11L, 12L), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyByNameWritesBackIdList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("name"), List.of("roleIds.name"), true, true, false, null);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("roleIds.name", "Admin,User"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("name")), anyCollection(), any()))
                .thenReturn(Map.of(List.of("Admin"), 21L, List.of("User"), 22L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(List.of(21L, 22L), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyByCompositeBusinessKeysWritesBackIdList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code", "name"), List.of("roleIds.code", "roleIds.name"), true, true, false, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleIds.code", "ADMIN,USER");
        row.put("roleIds.name", "Admin,User");

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("code", "name")), anyCollection(), any()))
                .thenReturn(Map.of(List.of("ADMIN", "Admin"), 31L, List.of("USER", "User"), 32L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(List.of(31L, 32L), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.code"));
        assertFalse(row.containsKey("roleIds.name"));
    }



    @Test
    @SuppressWarnings("unchecked")
    void aMultiColumnLookupSaysTheColumnsHaveToAgree() {
        // The employee child templates name the employee twice — by code and by name — so that a
        // mistyped code is caught by the name beside it. The columns are ANDed, so catching one looks
        // like "cannot find" over two values the reader knows both exist. Saying nothing more turns a
        // working check into an apparent bug report.
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "employeeId", "Employee", List.of("code", "fullName"),
                List.of("employeeId.code", "employeeId.fullName"), true, false, false, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("employeeId.code", "E1000123");
        row.put("employeeId.fullName", "Someone Else");

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Employee"), eq(List.of("code", "fullName")),
                anyCollection(), any())).thenReturn(Map.of());

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        String reason = row.get(FileConstant.FAILED_REASON).toString();
        assertTrue(reason.contains("code=E1000123"), reason);
        assertTrue(reason.contains("fullName=Someone Else"), reason);
        assertTrue(reason.contains("must all describe the same Employee"), reason);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSingleColumnLookupSaysNothingExtra() {
        // One column matching nothing needs no explanation, and every template that has ever used one
        // reads the message as it is today.
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "deptId", "Department", List.of("code"), List.of("deptId.code"), true, false, false, null);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("deptId.code", "D999"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code")), anyCollection(), any()))
                .thenReturn(Map.of());

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals("Department not found: D999", row.get(FileConstant.FAILED_REASON));
    }

    /**
     * A miss is named the way the sheet's reader knows the column — by the relation's label — rather
     * than by the model behind it and the field it was matched on.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aMissIsNamedByTheRelationsLabel() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            MetaField ipaPosition = metaField("Employee", "ipaPositionId", FieldType.MANY_TO_ONE, "IpaPosition");
            ReflectionTestUtils.setField(ipaPosition, "label", "IPA Position");
            mm.when(() -> ModelManager.getModelFieldOrNull("Employee", "ipaPositionId")).thenReturn(ipaPosition);
            RelationLookupResolver resolver = createResolver();
            var group = new RelationLookupResolver.LookupGroup("ipaPositionId", "IpaPosition", List.of("name"),
                    List.of("ipaPositionId.name"), true, false, false, null);
            Map<String, Object> row = new LinkedHashMap<>(Map.of("ipaPositionId.name", "Bar Bender"));
            ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
            when(typedService.getIdsByBusinessKeys(eq("IpaPosition"), eq(List.of("name")), anyCollection(), any()))
                    .thenReturn(Map.of());

            resolver.resolveRows("Employee", new ArrayList<>(List.of(row)), List.of(group), true);

            assertEquals("IPA Position not found: Bar Bender", row.get(FileConstant.FAILED_REASON));
        }
    }

    /** Several columns taken together still say which values were taken together, and why that failed. */
    @Test
    @SuppressWarnings("unchecked")
    void aMissOnSeveralColumnsNamesEachAndSaysTheyMustAgree() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code", "name"),
                List.of("deptId.code", "deptId.name"), true, false, false, null);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("deptId.code", "D1", "deptId.name", "Sales"));
        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code", "name")), anyCollection(), any()))
                .thenReturn(Map.of());

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals("Department not found: code=D1, name=Sales — these must all describe the same Department; "
                + "no one record matches them together.", row.get(FileConstant.FAILED_REASON));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyMarksFailedWhenCodeNotFound() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code"), List.of("roleIds.code"), true, true, false, null);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("roleIds.code", "ADMIN,UNKNOWN"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("code")), anyCollection(), any()))
                .thenReturn(Map.of(List.of("ADMIN"), 11L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertTrue(row.containsKey(FileConstant.FAILED_REASON));
        assertTrue(row.get(FileConstant.FAILED_REASON).toString().contains("Role not found: UNKNOWN"));
        assertFalse(row.containsKey("roleIds"));
    }

    /** A to-many column and a nested relation column are named by their own field's label too, like a to-one. */
    @Test
    @SuppressWarnings("unchecked")
    void everyKindOfRelationColumnIsNamedByItsLabel() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            MetaField roles = metaField("Employee", "roleIds", FieldType.MANY_TO_MANY, "Role");
            ReflectionTestUtils.setField(roles, "label", "Access Roles");
            mm.when(() -> ModelManager.getModelFieldOrNull("Employee", "roleIds")).thenReturn(roles);
            MetaField idType = metaField("Profile", "idType", FieldType.MANY_TO_ONE, "IdType");
            ReflectionTestUtils.setField(idType, "label", "ID Type");
            mm.when(() -> ModelManager.getModelFieldOrNull("Profile", "idType")).thenReturn(idType);
            RelationLookupResolver resolver = createResolver();
            var toMany = new RelationLookupResolver.LookupGroup(
                    "roleIds", "Role", List.of("code"), List.of("roleIds.code"), true, true, false, null);
            Map<String, Object> roleRow = new LinkedHashMap<>(Map.of("roleIds.code", "UNKNOWN"));
            Map<String, Object> idTypeRow = new LinkedHashMap<>(Map.of("profileId.idType.name", "Passport"));
            ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
            when(typedService.getIdsByBusinessKeys(any(), any(), anyCollection(), any())).thenReturn(Map.of());

            resolver.resolveRows("Employee", new ArrayList<>(List.of(roleRow)), List.of(toMany), true);
            resolver.resolveRows("Employee", new ArrayList<>(List.of(idTypeRow)), List.of(nestedGroup()), true);

            assertEquals("Access Roles not found: UNKNOWN", roleRow.get(FileConstant.FAILED_REASON));
            assertEquals("ID Type not found: Passport", idTypeRow.get(FileConstant.FAILED_REASON));
        }
    }

    @Test
    void resolveRowsToManyIgnoreEmptyFalseWritesEmptyList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code"), List.of("roleIds.code"), false, true, false, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleIds.code", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(Collections.emptyList(), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.code"));
    }

    @Test
    void resolveRowsToManyIgnoreEmptyFalseWithNameWritesEmptyList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("name"), List.of("roleIds.name"), false, true, false, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleIds.name", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(Collections.emptyList(), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.name"));
    }

    @Test
    void detectLookupGroupsCarriesTheRelationFieldsOwnFilters() {
        // The lookup has to be narrowed to the domain the FK field declares. Department.orgType points
        // at TenantOptionItem and names its option set in `filters`; itemCode is unique inside one set,
        // not across the table, so a lookup that ignores the filter can resolve another set's row — or
        // find two and fail the whole import as a duplicate business key.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            MetaField orgType = metaField("Department", "orgType", FieldType.MANY_TO_ONE, "TenantOptionItem");
            ReflectionTestUtils.setField(orgType, "filters", "[\"optionSetCode\", \"=\", \"OrganizationType\"]");
            mm.when(() -> ModelManager.existField("Department", "orgType")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("Department", "orgType")).thenReturn(orgType);
            RelationLookupResolver resolver = createResolver();

            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups(
                    "Department", List.of(importField("orgType.itemCode", null)));

            assertEquals(1, groups.size());
            assertNotNull(groups.getFirst().relationFilters());
            assertTrue(groups.getFirst().relationFilters().toString().contains("OrganizationType"));
        }
    }

    @Test
    void detectLookupGroupsLeavesFiltersNullWhenTheFieldDeclaresNone() {
        // The common case — a plain FK with no domain to narrow to. Null, not an empty Filters, so the
        // lookup keeps its single unfiltered query shape.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups(
                    "TestOrder", List.of(importField("deptId.code", null)));

            assertNull(groups.getFirst().relationFilters());
        }
    }

    // ------------------------------------------------- nested relation lookup (3 segments)

    @Test
    void detectLookupGroupsAcceptsAThreeSegmentPathThroughAOneToOne() {
        // The sub-record's own relations were unreachable by anything readable: two segments stop at
        // the relation and the cell must hold the foreign key itself. The third segment names the
        // field to look the target up by.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups(
                    "Employee",
                    List.of(importField("profileId.notes", null), importField("profileId.idType.name", null)));

            assertEquals(1, groups.size());
            RelationLookupResolver.LookupGroup group = groups.getFirst();
            assertTrue(group.oneToOne());
            assertEquals(1, group.nestedLookups().size());
            RelationLookupResolver.NestedLookup nested = group.nestedLookups().getFirst();
            assertEquals("idType", nested.nestedField());
            assertEquals("IdType", nested.relatedModel());
            assertEquals("name", nested.lookupField());
            assertEquals("profileId.idType.name", nested.dottedPath());
        }
    }

    @Test
    void detectLookupGroupsRejectsThreeSegmentsThroughAManyToOne() {
        // Through a many-to-one the two segments already are the business key of some other row; a
        // third would change what the column means, not extend it.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            assertThrows(IllegalArgumentException.class, () -> resolver.detectLookupGroups(
                    "TestOrder", List.of(importField("deptId.company.name", null))));
        }
    }

    @Test
    void detectLookupGroupsRejectsALeafThatIsItselfARelation() {
        // The leaf is what the cell carries, and a relation cannot be typed into a cell.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            assertThrows(IllegalArgumentException.class, () -> resolver.detectLookupGroups(
                    "Employee", List.of(importField("profileId.idType.country", null))));
        }
    }

    @Test
    void detectLookupGroupsRejectsTheSameNestedFieldAddressedTwice() {
        // profileId.idType holds the raw foreign key, profileId.idType.name resolves one — both write
        // the same nested field, and one of them would silently win.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            assertThrows(IllegalArgumentException.class, () -> resolver.detectLookupGroups(
                    "Employee",
                    List.of(importField("profileId.idType", null), importField("profileId.idType.name", null))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsLooksUpANestedRelationByItsNameInOneQuery() {
        RelationLookupResolver resolver = createResolver();
        var group = nestedGroup();
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("profileId.notes", "n1", "profileId.idType.name", "NRIC")),
                new LinkedHashMap<>(Map.of("profileId.idType.name", "FIN"))));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("IdType"), eq(List.of("name")), anyCollection(), any()))
                .thenAnswer(inv -> Map.of(List.of("NRIC"), "SG_NRIC", List.of("FIN"), "SG_FIN"));

        resolver.resolveRows(rows, List.of(group), true);

        assertEquals(Map.of("notes", "n1", "idType", "SG_NRIC"), rows.get(0).get("profileId"));
        assertEquals(Map.of("idType", "SG_FIN"), rows.get(1).get("profileId"));
        assertFalse(rows.get(0).containsKey("profileId.idType.name"));
        // One query for the whole sheet, not one per row — the same bargain every lookup strikes.
        verify(typedService, times(1))
                .getIdsByBusinessKeys(eq("IdType"), eq(List.of("name")), anyCollection(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsMarksTheRowWhenTheNestedNameMatchesNothing() {
        // A typo must not become a blank foreign key on the sub-record: the row fails and says which
        // value matched nothing, the same contract as every other lookup column.
        RelationLookupResolver resolver = createResolver();
        var group = nestedGroup();
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("profileId.idType.name", "NRIIC"))));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("IdType"), eq(List.of("name")), anyCollection(), any()))
                .thenAnswer(inv -> Map.of());

        resolver.resolveRows(rows, List.of(group), true);

        assertTrue(rows.getFirst().get(FileConstant.FAILED_REASON).toString()
                .contains("IdType not found: NRIIC"));
        assertFalse(rows.getFirst().containsKey("profileId.idType.name"));
    }

    @Test
    void resolveRowsLeavesANestedRelationAloneWhenBlank() {
        // Blank keeps the existing value, exactly like every other nested column — an update that
        // does not mention the id type must not clear it.
        RelationLookupResolver resolver = createResolver();
        var group = nestedGroup();
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("profileId.notes", "n1", "profileId.idType.name", " "))));

        resolver.resolveRows(rows, List.of(group), true);

        assertEquals(Map.of("notes", "n1"), rows.getFirst().get("profileId"));
        verifyNoInteractions(getModelService(resolver));
    }


    // ---- country-partitioned targets resolve in the row's own country -------------------------

    /**
     * "Full Time" is a different Employment Type in Singapore and in New Zealand. One file carrying both
     * countries' employees must resolve each row in the country of the company that row names — not in
     * the importer's countries, where the same name matches twice and fails as a duplicate key.
     */
    @Test
    @SuppressWarnings("unchecked")
    void resolvesAPartitionedTargetPerRowCountry() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            partitionedSetup(mm);
            RelationLookupResolver resolver = createResolver();
            CompanyCountryResolver countries = mock(CompanyCountryResolver.class);
            when(countries.resolveCountry(1L)).thenReturn("SG");
            when(countries.resolveCountry(2L)).thenReturn("NZ");
            ReflectionTestUtils.setField(resolver, "companyCountryResolver", countries);
            ModelService<Long> models = (ModelService<Long>) getModelService(resolver);
            when(models.getIdsByBusinessKeys(eq("EmploymentType"), eq(List.of("name")), anyCollection(), any()))
                    .thenAnswer(invocation -> {
                        Filters scope = invocation.getArgument(3);
                        String text = String.valueOf(scope);
                        return text.contains("\"SG\"") ? Map.of(List.<Object>of("Full Time"), 101L)
                                : Map.of(List.<Object>of("Full Time"), 202L);
                    });

            var group = new RelationLookupResolver.LookupGroup("employmentType", "EmploymentType",
                    List.of("name"), List.of("employmentType.name"), true, false, false, null);
            Map<String, Object> sgRow = new LinkedHashMap<>(Map.of("legalEntityId", 1L, "employmentType.name", "Full Time"));
            Map<String, Object> nzRow = new LinkedHashMap<>(Map.of("legalEntityId", 2L, "employmentType.name", "Full Time"));

            resolver.resolveRows("Employee", new ArrayList<>(List.of(sgRow, nzRow)), List.of(group), true);

            assertEquals(101L, sgRow.get("employmentType"));
            assertEquals(202L, nzRow.get("employmentType"));
            // One query per country, each bounded to that country on top of the field's own filters.
            ArgumentCaptor<Filters> scopes = ArgumentCaptor.forClass(Filters.class);
            verify(models, times(2)).getIdsByBusinessKeys(eq("EmploymentType"), eq(List.of("name")), anyCollection(), scopes.capture());
            assertTrue(scopes.getAllValues().stream().map(String::valueOf).anyMatch(t -> t.contains("[\"country\",\"=\",\"SG\"]")));
            assertTrue(scopes.getAllValues().stream().map(String::valueOf).anyMatch(t -> t.contains("[\"country\",\"=\",\"NZ\"]")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCompanyGroupResolvesFirstWhateverTheColumnOrder() {
        // The company itself may arrive as a lookup (legalEntityId.name). It has to be written back
        // before the partitioned lookup reads it, so the order is decided here, not by the template.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            partitionedSetup(mm);
            RelationLookupResolver resolver = createResolver();
            CompanyCountryResolver countries = mock(CompanyCountryResolver.class);
            when(countries.resolveCountry(1L)).thenReturn("SG");
            ReflectionTestUtils.setField(resolver, "companyCountryResolver", countries);
            ModelService<Long> models = (ModelService<Long>) getModelService(resolver);
            when(models.getIdsByBusinessKeys(eq("Company"), eq(List.of("name")), anyCollection(), any()))
                    .thenReturn(Map.of(List.<Object>of("Zingkey SG"), 1L));
            when(models.getIdsByBusinessKeys(eq("EmploymentType"), eq(List.of("name")), anyCollection(), any()))
                    .thenReturn(Map.of(List.<Object>of("Full Time"), 101L));

            var typeGroup = new RelationLookupResolver.LookupGroup("employmentType", "EmploymentType",
                    List.of("name"), List.of("employmentType.name"), true, false, false, null);
            var companyGroup = new RelationLookupResolver.LookupGroup("legalEntityId", "Company",
                    List.of("name"), List.of("legalEntityId.name"), true, false, false, null);
            Map<String, Object> row = new LinkedHashMap<>(Map.of("legalEntityId.name", "Zingkey SG", "employmentType.name", "Full Time"));

            // Type group listed first on purpose.
            resolver.resolveRows("Employee", new ArrayList<>(List.of(row)), List.of(typeGroup, companyGroup), true);

            assertEquals(1L, row.get("legalEntityId"));
            assertEquals(101L, row.get("employmentType"));
            verify(countries).resolveCountry(1L);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRowNamingNoCompanyResolvesUnbucketedAsBefore() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            partitionedSetup(mm);
            RelationLookupResolver resolver = createResolver();
            ReflectionTestUtils.setField(resolver, "companyCountryResolver", mock(CompanyCountryResolver.class));
            ModelService<Long> models = (ModelService<Long>) getModelService(resolver);
            when(models.getIdsByBusinessKeys(eq("EmploymentType"), eq(List.of("name")), anyCollection(), any()))
                    .thenReturn(Map.of(List.<Object>of("Full Time"), 101L));
            var group = new RelationLookupResolver.LookupGroup("employmentType", "EmploymentType",
                    List.of("name"), List.of("employmentType.name"), true, false, false, null);
            Map<String, Object> row = new LinkedHashMap<>(Map.of("employmentType.name", "Full Time"));

            resolver.resolveRows("Employee", new ArrayList<>(List.of(row)), List.of(group), true);

            assertEquals(101L, row.get("employmentType"));
            // No country bound: the caller's own set (MultiCountryScope) is the domain, as before.
            ArgumentCaptor<Filters> scope = ArgumentCaptor.forClass(Filters.class);
            verify(models).getIdsByBusinessKeys(eq("EmploymentType"), eq(List.of("name")), anyCollection(), scope.capture());
            assertTrue(Filters.isEmpty(scope.getValue()), String.valueOf(scope.getValue()));
        }
    }

    @Test
    void aTemplateWithoutACompanyColumnIsFlaggedForPartitionedLookups() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            partitionedSetup(mm);
            RelationLookupResolver resolver = createResolver();
            var group = new RelationLookupResolver.LookupGroup("employmentType", "EmploymentType",
                    List.of("name"), List.of("employmentType.name"), true, false, false, null);

            assertEquals(List.of("employmentType.name"), resolver.partitionedLookupsWithoutCompanyColumn(
                    "Employee", List.of(importField("employmentType.name", null), importField("code", null)), List.of(group)));
            assertTrue(resolver.partitionedLookupsWithoutCompanyColumn(
                    "Employee", List.of(importField("employmentType.name", null), importField("legalEntityId", null)), List.of(group)).isEmpty());
            assertTrue(resolver.partitionedLookupsWithoutCompanyColumn(
                    "Employee", List.of(importField("employmentType.name", null), importField("legalEntityId.name", null)), List.of(group)).isEmpty());
        }
    }

    /** Employee → legalEntityId (M2O Company) and employmentType (M2O EmploymentType, multiCountry). */
    private void partitionedSetup(MockedStatic<ModelManager> mm) {
        MetaField legalEntity = metaField("Employee", "legalEntityId", FieldType.MANY_TO_ONE, "Company");
        MetaField type = metaField("Employee", "employmentType", FieldType.MANY_TO_ONE, "EmploymentType");
        MetaModel employmentType = new MetaModel();
        ReflectionTestUtils.setField(employmentType, "multiCountry", true);
        MetaModel company = new MetaModel();
        mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        mm.when(() -> ModelManager.existModel("EmploymentType")).thenReturn(true);
        mm.when(() -> ModelManager.existModel("Company")).thenReturn(true);
        mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(legalEntity, type));
        mm.when(() -> ModelManager.getModel("EmploymentType")).thenReturn(employmentType);
        mm.when(() -> ModelManager.getModel("Company")).thenReturn(company);
    }

    private RelationLookupResolver.LookupGroup nestedGroup() {
        return new RelationLookupResolver.LookupGroup(
                "profileId", "Profile", List.of("notes", "idType.name"),
                List.of("profileId.notes", "profileId.idType.name"), true, false, true, null,
                List.of(new RelationLookupResolver.NestedLookup(
                        "idType", "IdType", "name", "profileId.idType.name", null)));
    }

    private RelationLookupResolver createResolver() {
        RelationLookupResolver resolver = new RelationLookupResolver();
        ReflectionTestUtils.setField(resolver, "modelService", mock(ModelService.class));
        return resolver;
    }

    private ModelService<?> getModelService(RelationLookupResolver resolver) {
        return (ModelService<?>) ReflectionTestUtils.getField(resolver, "modelService");
    }

    private void setupModelManager(MockedStatic<ModelManager> mm) {
        mm.when(() -> ModelManager.existField("TestOrder", "deptId")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "roleIds")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "status")).thenReturn(true);

        mm.when(() -> ModelManager.getModelField("TestOrder", "deptId"))
                .thenReturn(metaField("TestOrder", "deptId", FieldType.MANY_TO_ONE, "Department"));
        mm.when(() -> ModelManager.getModelField("TestOrder", "roleIds"))
                .thenReturn(metaField("TestOrder", "roleIds", FieldType.MANY_TO_MANY, "Role"));
        mm.when(() -> ModelManager.getModelField("TestOrder", "status"))
                .thenReturn(metaField("TestOrder", "status", FieldType.OPTION, null));

        // Employee → profileId (its own 1:1 sub-record) → Profile → idType (M2O) → IdType
        mm.when(() -> ModelManager.existField("Employee", "profileId")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("Employee", "profileId"))
                .thenReturn(metaField("Employee", "profileId", FieldType.ONE_TO_ONE, "Profile"));
        mm.when(() -> ModelManager.existField("Profile", "idType")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("Profile", "idType"))
                .thenReturn(metaField("Profile", "idType", FieldType.MANY_TO_ONE, "IdType"));
        mm.when(() -> ModelManager.existField("Profile", "notes")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("Profile", "notes"))
                .thenReturn(metaField("Profile", "notes", FieldType.STRING, null));
        mm.when(() -> ModelManager.existField("IdType", "name")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("IdType", "name"))
                .thenReturn(metaField("IdType", "name", FieldType.STRING, null));
        mm.when(() -> ModelManager.existField("IdType", "country")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("IdType", "country"))
                .thenReturn(metaField("IdType", "country", FieldType.MANY_TO_ONE, "CountryRegion"));

        // Everything past deptId is deliberately VALID: the rejection test below must fail on the
        // root's field type alone, or removing that gate would surface as a different error and the
        // test could not tell the difference.
        mm.when(() -> ModelManager.existField("Department", "company")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("Department", "company"))
                .thenReturn(metaField("Department", "company", FieldType.MANY_TO_ONE, "LegalEntity"));
        mm.when(() -> ModelManager.existField("LegalEntity", "name")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField("LegalEntity", "name"))
                .thenReturn(metaField("LegalEntity", "name", FieldType.STRING, null));
    }

    private ImportFieldDTO importField(String fieldName, Boolean ignoreEmpty) {
        ImportFieldDTO dto = new ImportFieldDTO();
        dto.setFieldName(fieldName);
        dto.setHeader(fieldName);
        dto.setIgnoreEmpty(ignoreEmpty);
        return dto;
    }

    private MetaField metaField(String modelName, String fieldName, FieldType fieldType, String relatedModel) {
        MetaField mf = new MetaField();
        ReflectionTestUtils.setField(mf, "modelName", modelName);
        ReflectionTestUtils.setField(mf, "fieldName", fieldName);
        ReflectionTestUtils.setField(mf, "fieldType", fieldType);
        ReflectionTestUtils.setField(mf, "label", fieldName);
        if (relatedModel != null) {
            ReflectionTestUtils.setField(mf, "relatedModel", relatedModel);
        }
        return mf;
    }
}
