package io.softa.framework.orm.service.validation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;

import io.softa.framework.orm.enums.AccessType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chain's contract: order, accumulation, fail-fast, and the merged row an update validator sees.
 */
class ModelWriteValidatorChainTest {

    private static ModelWriteValidatorChain chain(ModelWriteValidator... validators) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ModelWriteValidator> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenReturn(Stream.of(validators));
        return new ModelWriteValidatorChain(provider);
    }

    private static final List<String> CALLS = new ArrayList<>();

    @Order(110)
    static class CountryAddressRule implements ModelWriteValidator {
        @Override public boolean supports(String modelName) { return "Company".equals(modelName); }
        @Override public void validateCreate(WriteContext ctx) { check(ctx); }
        @Override public void validateUpdate(WriteContext ctx) { check(ctx); }
        private void check(WriteContext ctx) {
            CALLS.add("address");
            if ("SG".equals(ctx.row().get("country")) && ctx.row().get("postalCode") == null) {
                ctx.reject("postalCode", "Postal Code is required");
            }
        }
    }

    @Order(120)
    static class NameRule implements ModelWriteValidator {
        @Override public boolean supports(String modelName) { return "Company".equals(modelName); }
        @Override public void validateBatch(String modelName, List<Map<String, Object>> rows, AccessType accessType) { CALLS.add("name-batch"); }
        @Override public void validateCreate(WriteContext ctx) {
            CALLS.add("name");
            if (ctx.row().get("name") == null) ctx.reject("name", "Name is required");
        }
    }

    @Order(10)
    static class Precondition implements ModelWriteValidator {
        @Override public boolean supports(String modelName) { return true; }
        @Override public void validateCreate(WriteContext ctx) {
            CALLS.add("pre");
            if (Boolean.TRUE.equals(ctx.row().get("boom"))) ctx.fail("Tenant {0} is not configured", "t1");
        }
    }

    @Test
    void rejectionsAccumulateAcrossValidatorsAndRowsAndAreThrownOnce() {
        CALLS.clear();
        ModelWriteValidatorChain chain = chain(new Precondition(), new CountryAddressRule(), new NameRule());
        List<Map<String, Object>> rows = List.of(
                Map.of("country", "SG", "name", "Acme SG"),
                Map.of("country", "MY"));
        assertThatThrownBy(() -> chain.validateCreate("Company", rows))
                .isInstanceOfSatisfying(WriteValidationException.class, e -> {
                    assertThat(e.getErrors()).hasSize(2);
                    assertThat(e.fieldErrors()).containsKeys("postalCode", "name");
                    // two rows rejected: the message says which row each error belongs to
                    assertThat(e.getMessage()).contains("row 1 postalCode").contains("row 2 name");
                });
        // per validator: batch first, then the rows; validators in @Order
        assertThat(CALLS).containsExactly("pre", "pre", "address", "address", "name-batch", "name", "name");
    }

    @Test
    void failStopsImmediately() {
        CALLS.clear();
        ModelWriteValidatorChain chain = chain(new Precondition(), new NameRule());
        assertThatThrownBy(() -> chain.validateCreate("Company", List.of(Map.of("boom", true))))
                .isInstanceOf(WriteValidationException.class).hasMessageContaining("Tenant t1");
        assertThat(CALLS).containsExactly("pre");
    }

    @Test
    void anUpdateValidatorSeesThePatchMergedOntoTheStoredRow() {
        ModelWriteValidatorChain chain = chain(new CountryAddressRule());
        Map<Serializable, Map<String, Object>> originals = Map.of(7L, Map.of("id", 7L, "country", "MY", "postalCode", "12345"));
        // the patch does not send postalCode; the stored value keeps the rule satisfied
        assertThatCode(() -> chain.validateUpdate("Company", List.of(Map.of("id", 7L, "country", "SG")), originals))
                .doesNotThrowAnyException();
        Map<Serializable, Map<String, Object>> noCode = Map.of(7L, Map.of("id", 7L, "country", "MY"));
        assertThatThrownBy(() -> chain.validateUpdate("Company", List.of(Map.of("id", 7L, "country", "SG")), noCode))
                .isInstanceOf(WriteValidationException.class).hasMessageContaining("postalCode");
    }

    @Test
    void aTimelinePatchIsMergedOntoTheSliceItNamesNotOntoAnotherSliceOfTheSameId() {
        ModelWriteValidatorChain chain = chain(new CountryAddressRule());
        // two slices of logical id 7: the one the patch names has a postal code, the other does not
        Map<Serializable, Map<String, Object>> originals = Map.of(
                7L, Map.of("id", 7L, "sliceId", 72L, "country", "MY"),
                71L, Map.of("id", 7L, "sliceId", 71L, "country", "MY", "postalCode", "12345"),
                72L, Map.of("id", 7L, "sliceId", 72L, "country", "MY"));
        assertThatCode(() -> chain.validateUpdate("Company", List.of(Map.of("id", 7L, "sliceId", 71L, "country", "SG")), originals))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> chain.validateUpdate("Company", List.of(Map.of("id", 7L, "sliceId", 72L, "country", "SG")), originals))
                .isInstanceOf(WriteValidationException.class).hasMessageContaining("postalCode");
    }

    @Test
    void modelsWithoutAValidatorPayNothing() {
        ModelWriteValidatorChain chain = chain(new CountryAddressRule());
        assertThat(chain.supports("Employee")).isFalse();
        assertThatCode(() -> chain.validateCreate("Employee", List.of(Map.of()))).doesNotThrowAnyException();
        assertThatCode(() -> chain.validateDelete("Employee", List.of(1L))).doesNotThrowAnyException();
    }
}
