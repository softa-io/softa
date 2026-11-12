package io.softa.starter.es.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
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
import io.softa.starter.es.document.ChangeLogDocument;

/**
 * ChangeLog persist consumer
 * <p>
 * The subscription is Shared, so every replica takes messages off the topic; under the former
 * default (Exclusive) a second replica failed to boot. Each log is indexed as its own document,
 * so the order the replicas process messages in does not matter. Switching an existing deployment
 * from Exclusive needs one stop-then-start release: Pulsar refuses a Shared consumer while an
 * Exclusive one is still attached.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.change-log.topic")
public class ChangeLogPersistConsumer {

    @Value("${spring.elasticsearch.index.changelog}")
    private String index;

    @Autowired
    private ElasticsearchOperations esOperations;

    /**
     * Handle ChangeLog message from Pulsar.
     * @param changeLogMessage ChangeLog message
     */
    @PulsarListener(topics = "${mq.topics.change-log.topic}", subscriptionName = "${mq.topics.change-log.persist-sub}",
            subscriptionType = SubscriptionType.Shared)
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
            // Build document list — flatten Map payload fields to JSON strings on the wire
            List<IndexQuery> indexQueries = new ArrayList<>();
            for (ChangeLog changeLog : changeLogMessage.getChangeLogs()) {
                ChangeLogDocument doc = ChangeLogDocument.fromChangeLog(changeLog);
                IndexQuery indexQuery = new IndexQueryBuilder()
                        .withIndex(index)
                        .withId(UUID.randomUUID().toString())
                        .withObject(doc)
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
