package io.softa.framework.orm.utils;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.IllegalArgumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionLockUtilsTest {

    @Test
    void incrementSupportsIntegerVersionTypes() {
        assertThat(VersionLockUtils.increment(1)).isEqualTo(2);
        assertThat(VersionLockUtils.increment(1L)).isEqualTo(2L);
        assertThat(VersionLockUtils.increment((short) 1)).isEqualTo((short) 2);
        assertThat(VersionLockUtils.increment((byte) 1)).isEqualTo((byte) 2);
        assertThat(VersionLockUtils.increment(BigInteger.ONE)).isEqualTo(BigInteger.TWO);
    }

    @Test
    void decrementSupportsIntegerVersionTypes() {
        assertThat(VersionLockUtils.decrement(1)).isEqualTo(0);
        assertThat(VersionLockUtils.decrement(1L)).isEqualTo(0L);
        assertThat(VersionLockUtils.decrement((short) 1)).isEqualTo((short) 0);
        assertThat(VersionLockUtils.decrement((byte) 1)).isEqualTo((byte) 0);
        assertThat(VersionLockUtils.decrement(BigInteger.ONE)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void rejectsNullAndNonIntegerVersionTypes() {
        assertThatThrownBy(() -> VersionLockUtils.increment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        assertThatThrownBy(() -> VersionLockUtils.decrement("1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.class.getName());
    }
}
