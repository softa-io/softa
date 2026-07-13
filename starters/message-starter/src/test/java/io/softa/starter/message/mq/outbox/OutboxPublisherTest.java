package io.softa.starter.message.mq.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mq.MqProducer;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.shared.metrics.MessageMetrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the outbox publisher. The tests mock the service-level
 * versionLock transitions instead of asserting database-specific SQL.
 * <p>
 * The scan is route-filtered: only broker-available routes are queried, so
 * rows on unconfigured routes can never occupy (and starve) the id-ordered
 * scan batch.
 */
class OutboxPublisherTest {

    private OutboxService outboxService;
    private MqProducer mqProducer;
    private MessageMetrics metrics;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        outboxService = mock(OutboxService.class);
        mqProducer = mock(MqProducer.class);
        metrics = mock(MessageMetrics.class);
        publisher = new OutboxPublisher();
        ReflectionTestUtils.setField(publisher, "outboxService", outboxService);
        ReflectionTestUtils.setField(publisher, "mqProducer", mqProducer);
        ReflectionTestUtils.setField(publisher, "metrics", metrics);
    }

    private void stubOneClaim(TopicRoute route, String payload, long version, int attempts) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(1L);
        entry.setRoute(route);
        entry.setPayload(payload);
        entry.setVersion(version);
        entry.setAttempts(attempts);
        when(outboxService.findDueNew(anyInt(), any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of(entry));
        when(outboxService.markPublishing(1L, version)).thenReturn(true);
    }

    private static String payloadFor(long recordId) {
        return JsonUtils.objectToString(new OutboxMessage(recordId, 1L, "trace"));
    }

    @Test
    void emptyBatch_doesNothing() {
        when(mqProducer.isAvailable(TopicRoute.MAIL_SEND)).thenReturn(true);
        when(outboxService.findDueNew(anyInt(), any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of());

        publisher.pollAndPublish();

        verify(mqProducer, never()).sendAsync(any(), any());
        verify(outboxService, never()).markPublishing(any(), anyLong());
    }

    @Test
    void noAvailableRoutes_skipsScanEntirely() {
        // All routes unavailable → the publisher must not even query the outbox,
        // so unroutable rows never occupy the scan batch.
        publisher.pollAndPublish();

        verify(outboxService, never()).findDueNew(anyInt(), any(LocalDateTime.class), anyCollection());
        verify(mqProducer, never()).sendAsync(any(), any());
    }

    @Test
    void scanQueriesOnlyAvailableRoutes() {
        when(mqProducer.isAvailable(TopicRoute.MAIL_SEND)).thenReturn(true);
        // SMS routes stay unavailable (mock default false).
        when(outboxService.findDueNew(anyInt(), any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of());

        publisher.pollAndPublish();

        verify(outboxService).findDueNew(anyInt(), any(LocalDateTime.class),
                argThat((Collection<TopicRoute> routes) ->
                        routes.contains(TopicRoute.MAIL_SEND) && !routes.contains(TopicRoute.SMS_SEND)));
    }

    @Test
    void routeDropsBetweenScanAndDispatch_releasedWithDelay() {
        stubOneClaim(TopicRoute.MAIL_SEND, payloadFor(9L), 0L, 0);
        // Available during claimBatch's route scan, gone by dispatch time.
        when(mqProducer.isAvailable(TopicRoute.MAIL_SEND)).thenReturn(true, false);

        LocalDateTime before = LocalDateTime.now();
        publisher.pollAndPublish();

        verify(mqProducer, never()).sendAsync(any(), any());
        ArgumentCaptor<LocalDateTime> nextAttempt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxService).markNew(eq(1L), eq(1L), eq(0), isNull(), nextAttempt.capture());
        Assertions.assertTrue(nextAttempt.getValue().isAfter(before.plusSeconds(10)),
                "release must carry a real delay, not re-arm the row for the very next poll");
    }

    @Test
    void successfulPublish_marksPublished_andCountsMetric() {
        stubOneClaim(TopicRoute.MAIL_SEND, payloadFor(9L), 0L, 0);
        when(mqProducer.isAvailable(TopicRoute.MAIL_SEND)).thenReturn(true);
        when(mqProducer.sendAsync(eq(TopicRoute.MAIL_SEND), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.pollAndPublish();

        verify(outboxService).markPublished(1L, 1L);
        verify(metrics).outboxPublished("MAIL_SEND");
    }

    @Test
    void failedPublish_belowCap_schedulesBackoffRetry() {
        stubOneClaim(TopicRoute.SMS_SEND, payloadFor(9L), 0L, 0);
        when(mqProducer.isAvailable(TopicRoute.SMS_SEND)).thenReturn(true);
        when(mqProducer.sendAsync(eq(TopicRoute.SMS_SEND), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.pollAndPublish();

        verify(outboxService).markNew(eq(1L), eq(1L), eq(1), contains("broker down"), any(LocalDateTime.class));
        verify(metrics, never()).outboxDead(anyString());
    }

    @Test
    void failedPublish_atCap_movesToDead_andCountsMetric() {
        stubOneClaim(TopicRoute.SMS_SEND, payloadFor(9L), 0L, 9);
        when(mqProducer.isAvailable(TopicRoute.SMS_SEND)).thenReturn(true);
        when(mqProducer.sendAsync(eq(TopicRoute.SMS_SEND), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("still down")));

        publisher.pollAndPublish();

        verify(outboxService).markDead(eq(1L), eq(1L), eq(10), contains("still down"));
        verify(metrics).outboxDead("SMS_SEND");
    }

    @Test
    void badPayload_failsWithoutTouchingBroker() {
        stubOneClaim(TopicRoute.MAIL_SEND, "this-is-not-json", 0L, 0);
        when(mqProducer.isAvailable(TopicRoute.MAIL_SEND)).thenReturn(true);

        publisher.pollAndPublish();

        verify(mqProducer, never()).sendAsync(any(), any());
        verify(outboxService).markNew(eq(1L), eq(1L), eq(1), anyString(), any(LocalDateTime.class));
    }
}
