package io.softa.starter.message.push.cron;

import org.junit.jupiter.api.Test;

import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.message.push.service.AppPushDeviceService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The shared cron topic: message-starter answers DropStalePushDevices and ignores the rest. */
class PushDeviceCronConsumerTest {

    private final AppPushDeviceService devices = mock(AppPushDeviceService.class);
    private final PushDeviceCronConsumer consumer = new PushDeviceCronConsumer(devices);

    private static CronTaskMessage named(String cronName) {
        CronTaskMessage message = new CronTaskMessage();
        message.setCronName(cronName);
        return message;
    }

    @Test
    void dropStalePushDevices_dropsTheStaleRegistrations() {
        when(devices.dropStale()).thenReturn(3);

        consumer.onMessage(named(PushDeviceCronConsumer.DROP_STALE_PUSH_DEVICES));

        verify(devices).dropStale();
    }

    @Test
    void anotherModulesCronIsIgnored() {
        consumer.onMessage(named("DynamicRoleSync"));
        consumer.onMessage(null);
        consumer.onMessage(new CronTaskMessage());

        verifyNoInteractions(devices);
    }

    @Test
    void aFailedDropIsLogged_neverRethrown() {
        when(devices.dropStale()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> consumer.onMessage(named(PushDeviceCronConsumer.DROP_STALE_PUSH_DEVICES)))
                .doesNotThrowAnyException();
    }
}
