package io.softa.starter.user.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A registry written before {@code unregisterAccount} existed still compiles and still works: the
 * method is a default that does nothing.
 */
class PushDeviceRegistryTest {

    @Test
    void aRegistryWithoutUnregisterAccount_compiles_andTheCallIsANoOp() {
        PushDeviceRegistry older = new PushDeviceRegistry() {
            @Override
            public void unregisterDevice(String deviceToken, Long profileId) {
            }

            @Override
            public void unregisterPerson(Long profileId) {
            }
        };

        assertThatCode(() -> older.unregisterAccount(42L)).doesNotThrowAnyException();
    }
}
