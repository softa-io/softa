package io.softa.framework.orm.changelog.message;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;

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
        }
        Assert.notBlank(changeLogTopic, "ChangeLog topic is not configured");
        Assert.notNull(pulsarTemplate, "Pulsar is not configured");
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
    }
}
