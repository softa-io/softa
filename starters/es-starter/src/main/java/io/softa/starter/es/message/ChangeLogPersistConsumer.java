package io.softa.starter.es.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;

/**
 * ChangeLog persist consumer
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "system", name = "enable-change-log", havingValue = "true")
public class ChangeLogPersistConsumer {

    @Value("${spring.elasticsearch.index.changelog}")
    private String index;

    @Autowired
    private ElasticsearchOperations esOperations;

    /**
     * Handle ChangeLog message from Pulsar.
     * @param changeLogMessage ChangeLog message
     */
    @PulsarListener(topics = "${mq.topics.change-log.topic}", subscriptionName = "${mq.topics.change-log.persist-sub}")
    public void onMessage(ChangeLogMessage changeLogMessage) {
        persistChangeLogToESDirectly(changeLogMessage);
    }

    /**
     * Persist change log to ES directly
     * @param changeLogMessage ChangeLog message
     */
    public void persistChangeLogToESDirectly(ChangeLogMessage changeLogMessage) {
        Context ctx = changeLogMessage.getContext();
        ContextHolder.runWith(ctx, () -> {
            // Build document list
            List<IndexQuery> indexQueries = new ArrayList<>();
            for (ChangeLog changeLog : changeLogMessage.getChangeLogs()) {
                IndexQuery indexQuery = new IndexQueryBuilder()
                        .withIndex(index)
                        .withId(UUID.randomUUID().toString())
                        .withObject(changeLog)
                        .build();
                indexQueries.add(indexQuery);
            }
            try {
                esOperations.bulkIndex(indexQueries, IndexCoordinates.of(index));
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        });
    }

}
