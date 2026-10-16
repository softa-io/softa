package io.softa.framework.base.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Guards the global empty-string→null scalar coercions in {@link JacksonConfig}.
 * Without them, form clears of optional selects / numbers / toggles ({@code ""}) blow
 * up at {@code @RequestBody} binding as {@code InvalidFormatException}.
 */
class JacksonEmptyEnumCoercionTest {

    private enum Sample {
        A,
        B
    }

    private record EnumHolder(Sample value) {}

    private record ScalarHolder(Long count, BigDecimal amount, Boolean ok) {}

    private static JsonMapper mapperWithSoftaPolicy() {
        return JacksonConfig.applyGlobalPolicy(JsonMapper.builder()).build();
    }

    @Test
    void emptyString_bindsAsNull_forEnum() throws Exception {
        EnumHolder holder = mapperWithSoftaPolicy().readValue("{\"value\":\"\"}", EnumHolder.class);
        assertThat(holder.value()).isNull();
    }

    @Test
    void emptyString_bindsAsNull_forIntegerFloatBoolean() throws Exception {
        ScalarHolder holder = mapperWithSoftaPolicy().readValue(
                "{\"count\":\"\",\"amount\":\"\",\"ok\":\"\"}", ScalarHolder.class);
        assertThat(holder.count()).isNull();
        assertThat(holder.amount()).isNull();
        assertThat(holder.ok()).isNull();
    }

    @Test
    void validValues_stillBind() throws Exception {
        EnumHolder enums = mapperWithSoftaPolicy().readValue("{\"value\":\"A\"}", EnumHolder.class);
        assertThat(enums.value()).isEqualTo(Sample.A);

        ScalarHolder scalars = mapperWithSoftaPolicy().readValue(
                "{\"count\":\"12\",\"amount\":\"1.5\",\"ok\":\"true\"}", ScalarHolder.class);
        assertThat(scalars.count()).isEqualTo(12L);
        assertThat(scalars.amount()).isEqualByComparingTo("1.5");
        assertThat(scalars.ok()).isTrue();
    }

    /** Without an application context JsonUtils builds its own mapper, and that mapper applies the same coercions. */
    @Test
    void fallbackMapper_outsideSpring_followsThePolicy() {
        ScalarHolder holder = JsonUtils.stringToObject("{\"count\":\"\",\"amount\":\"\",\"ok\":\"\"}", ScalarHolder.class);
        assertThat(holder.count()).isNull();
        assertThat(holder.amount()).isNull();
        assertThat(holder.ok()).isNull();
    }

    @Test
    void unknownEnumCode_stillFails() {
        // Only empty string is coerced; garbage must not be silently nullified.
        assertThatThrownBy(() -> mapperWithSoftaPolicy().readValue("{\"value\":\"NOPE\"}", EnumHolder.class))
                .isInstanceOf(DatabindException.class);
    }

    @Test
    void withoutCoercion_emptyStringFails() {
        JsonMapper strict = JsonMapper.builder().build();
        assertThatThrownBy(() -> strict.readValue("{\"value\":\"\"}", EnumHolder.class))
                .isInstanceOf(DatabindException.class);
    }
}
