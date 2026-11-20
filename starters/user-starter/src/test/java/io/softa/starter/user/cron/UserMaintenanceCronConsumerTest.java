package io.softa.starter.user.cron;

import org.junit.jupiter.api.Test;

import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.user.service.DynamicRoleSyncJob;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The shared cron topic: user-starter answers its own name and ignores the rest. */
class UserMaintenanceCronConsumerTest {

    private final DynamicRoleSyncJob roleSync = mock(DynamicRoleSyncJob.class);
    private final UserMaintenanceCronConsumer consumer = new UserMaintenanceCronConsumer(roleSync);

    private static CronTaskMessage named(String cronName) {
        CronTaskMessage message = new CronTaskMessage();
        message.setCronName(cronName);
        return message;
    }

    @Test
    void dynamicRoleSync_runsTheSync() {
        consumer.onMessage(named(UserMaintenanceCronConsumer.DYNAMIC_ROLE_SYNC));

        verify(roleSync).syncAll();
    }

    @Test
    void anotherModulesCronIsIgnored_theStalePushDevicesIncluded() {
        consumer.onMessage(named("DropStalePushDevices"));
        consumer.onMessage(named("SweepEmployeeStatus"));
        consumer.onMessage(null);
        consumer.onMessage(new CronTaskMessage());

        verifyNoInteractions(roleSync);
    }

    @Test
    void aFailedSyncIsLogged_neverRethrown() {
        doThrow(new IllegalStateException("database down")).when(roleSync).syncAll();

        assertThatCode(() -> consumer.onMessage(named(UserMaintenanceCronConsumer.DYNAMIC_ROLE_SYNC)))
                .doesNotThrowAnyException();
    }
}
