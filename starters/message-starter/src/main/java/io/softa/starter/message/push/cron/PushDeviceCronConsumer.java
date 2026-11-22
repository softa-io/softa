package io.softa.starter.message.push.cron;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.message.push.service.AppPushDeviceService;

/**
 * Runs {@code DropStalePushDevices} ({@code data-system/SysCron.MessageMaintenance.json}) from the shared
 * {@code cron-task} broadcast, under a subscription of its own; any other cron name is ignored. CrossTenant: one
 * message, and the service reads every tenant's rows itself. It removes the registrations whose session has ended on
 * its own, which announces nothing; senders already skip them, so this only keeps the table small.
 */
@Slf4j
@Component
@ConditionalOnClass(name = "io.softa.starter.cron.message.dto.CronTaskMessage")
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class PushDeviceCronConsumer {

    /** Must equal the {@code sys_cron.name} in data-system/SysCron.MessageMaintenance.json. */
    static final String DROP_STALE_PUSH_DEVICES = "DropStalePushDevices";

    private final AppPushDeviceService deviceService;

    public PushDeviceCronConsumer(AppPushDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PulsarListener(topics = "${mq.topics.cron-task.topic}",
            subscriptionName = "${mq.topics.cron-task.push-device-sub:cron-task-push-device-sub}",
            subscriptionType = SubscriptionType.Shared)
    public void onMessage(CronTaskMessage message) {
        if (message == null || !DROP_STALE_PUSH_DEVICES.equals(message.getCronName())) {
            return;
        }
        try {
            int dropped = deviceService.dropStale();
            if (dropped > 0) {
                log.info("[CRON] {} — dropped {} stale push device(s)", DROP_STALE_PUSH_DEVICES, dropped);
            }
        } catch (RuntimeException e) {
            // Swallowed: rethrowing on a Shared subscription would redeliver in a tight loop. The next night tries again.
            log.error("[CRON_FAILURE] {} failed: {}", DROP_STALE_PUSH_DEVICES, e.toString(), e);
        }
    }
}
