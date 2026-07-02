package io.softa.starter.user.filter;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.SensitiveFieldSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveFieldSetCacheTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelService modelService;
    private SensitiveFieldSetCache cache;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        cache = new SensitiveFieldSetCache(modelService);
    }

    private static SensitiveFieldSet sfs(String id, String model, String name, List<String> codes, List<String> attached) {
        SensitiveFieldSet s = new SensitiveFieldSet();
        s.setId(id);
        s.setModel(model);
        s.setName(name);
        ArrayNode codesArr = JSON.arrayNode();
        codes.forEach(codesArr::add);
        s.setFieldCodes(codesArr);
        if (attached != null) {
            ArrayNode attachedArr = JSON.arrayNode();
            attached.forEach(attachedArr::add);
            s.setAttachedTo(attachedArr);
        }
        return s;
    }

    private void primeWith(List<SensitiveFieldSet> rows) {
        when(modelService.searchList(
                eq("SensitiveFieldSet"), any(FlexQuery.class), eq(SensitiveFieldSet.class)))
                .thenReturn(rows);
        cache.reload();
    }

    @Test
    void reload_loadFailure_throws() {
        when(modelService.searchList(
                eq("SensitiveFieldSet"), any(FlexQuery.class), eq(SensitiveFieldSet.class)))
                .thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> cache.reload())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SensitiveFieldSetCache load failed");
    }

    @Test
    void reload_skipsRowsWithNullIdOrModel() {
        SensitiveFieldSet bad1 = new SensitiveFieldSet();
        bad1.setModel("Employee");
        SensitiveFieldSet bad2 = new SensitiveFieldSet();
        bad2.setId("noModel");
        primeWith(List.of(bad1, bad2, sfs("comp", "Employee", "Compensation", List.of("salary"), null)));

        assertThat(cache.hasSensitiveFieldsOn("Employee")).isTrue();
        assertThat(cache.allSensitiveFieldsOn("Employee")).containsExactly("salary");
    }

    @Test
    void modelOf_knownSetId_returnsModel() {
        primeWith(List.of(sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.modelOf("comp")).isEqualTo("Employee");
    }

    @Test
    void modelOf_unknownSetId_returnsNull() {
        primeWith(List.of());
        assertThat(cache.modelOf("nope")).isNull();
    }

    @Test
    void hasSensitiveFieldsOn_unknownModel_false() {
        primeWith(List.of(sfs("comp", "Employee", "Comp", List.of("salary"), null)));
        assertThat(cache.hasSensitiveFieldsOn("Department")).isFalse();
    }

    @Test
    void allSensitiveFieldsOn_unionsAcrossSets() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary", "bonus"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        assertThat(cache.allSensitiveFieldsOn("Employee"))
                .containsExactlyInAnyOrder("salary", "bonus", "bankAccount");
    }

    @Test
    void grantedFieldsFor_ignoresGrantsBoundToDifferentModel() {
        // "comp" is on Employee. If asker requests grant for Department using
        // setId "comp", it must return empty — no cross-model leak.
        primeWith(List.of(sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.grantedFieldsFor("Department", Set.of("comp"))).isEmpty();
    }

    @Test
    void grantedFieldsFor_returnsUnionOfMatchingSets() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        assertThat(cache.grantedFieldsFor("Employee", Set.of("comp", "bank")))
                .containsExactlyInAnyOrder("salary", "bankAccount");
    }

    @Test
    void computeForbiddenFields_grantedCoversAll_returnsEmpty() {
        primeWith(List.of(sfs("comp", "Employee", "Comp", List.of("salary"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Employee", Set.of("comp"));
        assertThat(blocked).isEmpty();
    }

    @Test
    void computeForbiddenFields_partialGrant_leavesRestBlocked() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Employee", Set.of("comp"));
        assertThat(blocked).containsExactly("bankAccount");
    }

    @Test
    void computeForbiddenFields_noGrants_allSensitiveBlocked() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Employee", Set.of());
        assertThat(blocked).containsExactlyInAnyOrder("salary", "bankAccount");
    }

    @Test
    void computeForbiddenFields_noSensitiveOnModel_earlyEmpty() {
        primeWith(List.of(sfs("comp", "Employee", "Comp", List.of("salary"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Department", Set.of("comp"));
        assertThat(blocked).isEmpty();
    }

    @Test
    void setIdsAttachedTo_findsAttachmentHint() {
        primeWith(List.of(
                sfs("bank", "EmpBankAccount", "Bank", List.of("acctNo"), List.of("Employee"))));
        assertThat(cache.setIdsAttachedTo("Employee")).containsExactly("bank");
    }

    @Test
    void setIdsOwnedBy_findsCanonicalOwnership() {
        primeWith(List.of(
                sfs("bank", "EmpBankAccount", "Bank", List.of("acctNo"), null),
                sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.setIdsOwnedBy("EmpBankAccount")).containsExactly("bank");
        assertThat(cache.setIdsOwnedBy("Employee")).containsExactly("comp");
    }

    @Test
    void nameOf_returnsDisplayName() {
        primeWith(List.of(sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.nameOf("comp")).isEqualTo("Compensation");
        assertThat(cache.nameOf("unknown")).isNull();
    }
}
