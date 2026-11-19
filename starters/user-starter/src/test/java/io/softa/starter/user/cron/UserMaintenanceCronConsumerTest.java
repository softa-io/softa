package io.softa.starter.user.cron;

import org.junit.jupiter.api.Test;

import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.user.service.AppPushDeviceService;
import io.softa.starter.user.service.DynamicRoleSyncJob;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The shared cron topic: user-starter answers its own two names and ignores the rest. */
class UserMaintenanceCronConsumerTest {

    private final DynamicRoleSyncJob roleSync = mock(DynamicRoleSyncJob.class);
    private final AppPushDeviceService pushDevices = mock(AppPushDeviceService.class);
    private final UserMaintenanceCronConsumer consumer = new UserMaintenanceCronConsumer(roleSync, pushDevices);

    private static CronTaskMessage named(String cronName) {
        CronTaskMessage message = new CronTaskMessage();
        message.setCronName(cronName);
        return message;
    }

    @Test
    void dropStalePushDevices_dropsTheStaleRegistrations_andNothingElse() {
        when(pushDevices.dropStale()).thenReturn(3);

        consumer.onMessage(named(UserMaintenanceCronConsumer.DROP_STALE_PUSH_DEVICES));

        verify(pushDevices).dropStale();
        verify(roleSync, never()).syncAll();
    }

    @Test
    void anotherModulesCronIsIgnored() {
        consumer.onMessage(named("SweepEmployeeStatus"));
        consumer.onMessage(null);
        consumer.onMessage(new CronTaskMessage());

        verifyNoInteractions(pushDevices, roleSync);
    }

    @Test
    void aFailedDropIsLogged_neverRethrown() {
        when(pushDevices.dropStale()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> consumer.onMessage(named(UserMaintenanceCronConsumer.DROP_STALE_PUSH_DEVICES)))
                .doesNotThrowAnyException();
    }
}
