package io.softa.starter.metadata.cron;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.meta.AppStartup;

/**
 * Cron job to periodically reload metadata in local mode (without MQ).
 */
@Component
@ConditionalOnExpression("'${mq.topics.inner-broadcast.topic:}'.trim().isEmpty()")
public class ReloadLocalMetadataCron {

    @Autowired
    private AppStartup appStartup;

    /**
     * Trigger metadata reload every minute.
     */
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    public void triggerReload() {
        reloadOnVirtualThread();
    }

    /**
     * Reload metadata asynchronously on a virtual thread.
     */
    @Async
    protected void reloadOnVirtualThread() {
        appStartup.reloadMetadata();
    }

}
