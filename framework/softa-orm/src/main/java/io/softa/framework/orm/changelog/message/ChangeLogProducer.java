package io.softa.framework.orm.changelog.message;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;

/**
 * ChangeLog Producer, send ChangeLog to MQ
 */
@Slf4j
@Component
public class ChangeLogProducer {

    @Value("${mq.topics.change-log.topic:}")
    private String changeLogTopic;

    @Autowired(required = false)
    private PulsarTemplate<ChangeLogMessage> pulsarTemplate;

    /**
     * Send ChangeLog to MQ in batches to avoid exceeding the message size limit
     */
    public void sendChangeLog(List<ChangeLog> changeLogs) {
        if (!SystemConfig.env.isEnableChangeLog() || CollectionUtils.isEmpty(changeLogs)) {
            log.info("ChangeLog is disabled or empty, skip sending");
            return;
        }
        if (StringUtils.isBlank(changeLogTopic)) {
            log.warn("ChangeLog topic is not configured, skip sending");
            return;
        }
        if (pulsarTemplate == null) {
            log.warn("Pulsar is not configured, skip sending ChangeLog");
            return;
        }
        try {
            Context clonedContext = ContextHolder.cloneContext();
            for (int i = 0; i < changeLogs.size(); i += BaseConstant.DEFAULT_PAGE_SIZE) {
                List<ChangeLog> changeLogBatch = changeLogs.subList(i, Math.min(i + BaseConstant.DEFAULT_PAGE_SIZE, changeLogs.size()));
                // Create ChangeLogMessage
                ChangeLogMessage changeLogMessage = new ChangeLogMessage(changeLogBatch, clonedContext);
                // Send ChangeLogMessage asynchronously
                pulsarTemplate.sendAsync(changeLogTopic, changeLogMessage).whenComplete((_, ex) -> {
                    if (ex != null) {
                        log.error("ChangeLog send to MQ failed: ", ex);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to send ChangeLog to MQ: ", e);
        }
    }
}
