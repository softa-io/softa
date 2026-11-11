package io.softa.starter.message.mail.message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailReceiveServer;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailReceiveServerService;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.softa.starter.message.shared.TenantScopes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Coverage for the scheduled-fetch consumer: it must (1) ignore cron events
 * whose name is not the {@code mail-fetch} prefix, (2) poll every enabled
 * receive config, each inside the tier that owns it, and (3) isolate a
 * per-config fetch failure so the remaining configs are still polled.
 */
class CronTaskMailFetchConsumerTest {

    private MailReceiveService mailReceiveService;
    private MailReceiveServerConfigService receiveConfigService;
    private PlatformMailReceiveServerService platformReceiveService;
    private CronTaskMailFetchConsumer consumer;

    @BeforeEach
    void setUp() {
        mailReceiveService = mock(MailReceiveService.class);
        receiveConfigService = mock(MailReceiveServerConfigService.class);
        platformReceiveService = mock(PlatformMailReceiveServerService.class);
        consumer = new CronTaskMailFetchConsumer();
        ReflectionTestUtils.setField(consumer, "mailReceiveService", mailReceiveService);
        ReflectionTestUtils.setField(consumer, "receiveConfigService", receiveConfigService);
        ReflectionTestUtils.setField(consumer, "platformReceiveService", platformReceiveService);
        when(platformReceiveService.searchList(any(FlexQuery.class))).thenReturn(List.of());
    }

    private static CronTaskMessage cron(String name) {
        CronTaskMessage m = new CronTaskMessage();
        m.setCronId(1L);
        m.setCronName(name);
        return m;
    }

    private static MailReceiveServerConfig config(long id) {
        MailReceiveServerConfig c = new MailReceiveServerConfig();
        c.setId(id);
        c.setHost("imap.example.com");
        return c;
    }

    @Test
    void ignoresNullCronName() {
        consumer.onMessage(new CronTaskMessage());
        verifyNoInteractions(receiveConfigService, mailReceiveService);
        verify(platformReceiveService, never()).searchList(any(FlexQuery.class));
    }

    @Test
    void ignoresNonMatchingCronName() {
        consumer.onMessage(cron("some-other-job"));
        verifyNoInteractions(receiveConfigService, mailReceiveService);
    }

    @Test
    void noEnabledConfigs_doesNotFetch() {
        when(receiveConfigService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        consumer.onMessage(cron("mail-fetch"));
        verify(mailReceiveService, never()).fetchNewMails(anyLong());
    }

    @Test
    void fetchesFromEachEnabledConfig() {
        when(receiveConfigService.searchList(any(FlexQuery.class)))
                .thenReturn(List.of(config(1L), config(2L)));
        when(mailReceiveService.fetchNewMails(1L)).thenReturn(3);
        when(mailReceiveService.fetchNewMails(2L)).thenReturn(2);

        consumer.onMessage(cron("mail-fetch-hourly")); // prefix match

        verify(mailReceiveService).fetchNewMails(1L);
        verify(mailReceiveService).fetchNewMails(2L);
    }

    @Test
    void eachServerIsFetchedInsideItsOwnersContext() {
        // Mail belongs to the owner of the server it arrived through: the platform's servers are
        // fetched in the platform's context into its own table, a tenant's in that tenant's.
        PlatformMailReceiveServer platformServer = new PlatformMailReceiveServer();
        platformServer.setId(1L);
        platformServer.setHost("imap.platform.example.com");
        MailReceiveServerConfig tenantServer = config(2L);
        tenantServer.setTenantId(7L);
        when(platformReceiveService.searchList(any(FlexQuery.class))).thenReturn(List.of(platformServer));
        when(receiveConfigService.searchList(any(FlexQuery.class))).thenReturn(List.of(tenantServer));
        Map<Long, Long> tenantSeen = new HashMap<>();
        when(mailReceiveService.fetchPlatformMails(anyLong())).thenAnswer(inv -> {
            Context ctx = ContextHolder.getContext();
            assertFalse(ctx.isCrossTenant());
            tenantSeen.put(inv.getArgument(0), ctx.getTenantId());
            return 1;
        });
        when(mailReceiveService.fetchNewMails(anyLong())).thenAnswer(inv -> {
            Context ctx = ContextHolder.getContext();
            assertFalse(ctx.isCrossTenant());
            tenantSeen.put(inv.getArgument(0), ctx.getTenantId());
            return 1;
        });

        consumer.onMessage(cron("mail-fetch"));

        assertEquals(Map.of(1L, TenantScopes.PLATFORM, 2L, 7L), tenantSeen);
        verify(mailReceiveService, never()).fetchNewMails(1L);
        verify(mailReceiveService, never()).fetchPlatformMails(2L);
    }

    @Test
    void perConfigFailureIsIsolated() {
        when(receiveConfigService.searchList(any(FlexQuery.class)))
                .thenReturn(List.of(config(1L), config(2L)));
        when(mailReceiveService.fetchNewMails(1L)).thenThrow(new RuntimeException("imap down"));
        when(mailReceiveService.fetchNewMails(2L)).thenReturn(5);

        assertDoesNotThrow(() -> consumer.onMessage(cron("mail-fetch")));

        verify(mailReceiveService).fetchNewMails(1L);
        verify(mailReceiveService).fetchNewMails(2L);
    }
}
