package io.softa.framework.orm.changelog.message;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * ChangeLog Producer, send ChangeLog to MQ
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "system", name = "enable-change-log", havingValue = "true")
public class ChangeLogProducer {

    @Value("${mq.topics.change-log.topic:}")
    private String changeLogTopic;

    @Autowired(required = false)
    private PulsarTemplate<ChangeLogMessage> pulsarTemplate;

    /**
     * Send ChangeLog to MQ in batches to avoid exceeding the message size limit
     */
    public void sendChangeLog(List<ChangeLog> changeLogs) {
        if (CollectionUtils.isEmpty(changeLogs)) {
            return;
        } else if (StringUtils.isBlank(changeLogTopic)) {
            log.warn("mq.topics.change-log.topic not configured!");
            return;
        } else if (pulsarTemplate == null) {
            log.warn("Pulsar not configured!");
            return;
        }
        Context clonedContext = ContextHolder.cloneContext();
        for (int i = 0; i < changeLogs.size(); i += BaseConstant.DEFAULT_PAGE_SIZE) {
            List<ChangeLog> changeLogBatch = changeLogs.subList(i, Math.min(i + BaseConstant.DEFAULT_PAGE_SIZE, changeLogs.size()));
            // Create ChangeLogMessage
            ChangeLogMessage changeLogMessage = new ChangeLogMessage(changeLogBatch, clonedContext);
            // Send ChangeLogMessage asynchronously
            pulsarTemplate.sendAsync(changeLogTopic, changeLogMessage).whenComplete((messageId, ex) -> {
                if (ex != null) {
                    log.error("ChangeLog send to MQ failed: ", ex);
                }
            });
        }
    }
}
