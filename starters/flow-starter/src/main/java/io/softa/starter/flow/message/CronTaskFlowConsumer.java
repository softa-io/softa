package io.softa.starter.flow.message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.starter.cron.entity.SysCronLog;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.cron.service.SysCronLogService;
import io.softa.starter.flow.FlowAutomation;

/**
 * Cron task consumer for Flow
 */
@Component
public class CronTaskFlowConsumer {

    @Autowired
    private FlowAutomation flowAutomation;

    @Autowired
    private SysCronLogService cronLogService;

    @PulsarListener(topics = "${mq.topics.cron-task.topic}", subscriptionName = "${mq.topics.cron-task.flow-sub}")
    @SwitchUser(SystemUser.CRON_USER)
    public void onMessage(CronTaskMessage cronTaskMessage) {
        persistCronLog(cronTaskMessage);
        flowAutomation.cronEvent(cronTaskMessage);
    }

    /**
     * Persist the cron task execution log
     *
     * @param cronTaskMessage Cron task message
     */
    private void persistCronLog(CronTaskMessage cronTaskMessage) {
        SysCronLog cronLog = new SysCronLog();
        cronLog.setCronId(cronTaskMessage.getCronId());
        cronLog.setCronName(cronTaskMessage.getCronName());
        cronLog.setStartTime(cronTaskMessage.getTriggerTime());
        cronLogService.createOne(cronLog);
    }
}