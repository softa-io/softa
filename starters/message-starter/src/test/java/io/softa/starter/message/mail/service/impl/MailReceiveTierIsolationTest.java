package io.softa.starter.message.mail.service.impl;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailReceiveServer;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailReceiveServerService;
import io.softa.starter.message.mail.support.MailServerDispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Receiving never crosses owners: a tenant fetches only through its own receive servers and the
 * platform only through the platform's. The two live in separate tables, so a tenant without a receive
 * server of its own has nothing to fetch — never the platform's mailbox — and a platform server's id
 * means nothing in the tenant's table.
 */
class MailReceiveTierIsolationTest {

    private static final long PLATFORM_SERVER = 100L;
    private static final long TENANT_SERVER = 200L;

    private MailReceiveServerConfigService tenantServers;
    private PlatformMailReceiveServerService platformServers;
    private MailServerDispatcher dispatcher;
    private MailReceiveServiceImpl receiveService;

    @BeforeEach
    void setUp() {
        tenantServers = mock(MailReceiveServerConfigService.class);
        platformServers = mock(PlatformMailReceiveServerService.class);
        dispatcher = new MailServerDispatcher();
        ReflectionTestUtils.setField(dispatcher, "receiveConfigService", tenantServers);
        ReflectionTestUtils.setField(dispatcher, "platformReceiveService", platformServers);
        receiveService = new MailReceiveServiceImpl();
        ReflectionTestUtils.setField(receiveService, "dispatcher", dispatcher);

        PlatformMailReceiveServer platformDefault = new PlatformMailReceiveServer();
        platformDefault.setId(PLATFORM_SERVER);
        platformDefault.setIsDefault(true);
        when(platformServers.findDefault()).thenReturn(Optional.of(platformDefault));
        when(platformServers.getById(PLATFORM_SERVER)).thenReturn(Optional.of(platformDefault));
        MailReceiveServerConfig tenantServer = new MailReceiveServerConfig();
        tenantServer.setId(TENANT_SERVER);
        when(tenantServers.findDefault()).thenReturn(Optional.empty());
        when(tenantServers.getById(TENANT_SERVER)).thenReturn(Optional.of(tenantServer));
    }

    @Test
    void tenantWithoutAReceiveServerIsToldNotConfigured() {
        // The platform has a default receive server; the tenant has none of its own.
        BusinessException ex = assertThrows(BusinessException.class, () -> receiveService.fetchNewMails());

        assertThat(ex.getMessage()).contains("No receiving mail server is configured");
        verifyNoInteractions(platformServers);
    }

    @Test
    void tenantCannotFetchThroughThePlatformsServerById() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> receiveService.fetchNewMails(PLATFORM_SERVER));

        assertThat(ex.getMessage()).contains("not found");
        verifyNoInteractions(platformServers);
    }

    @Test
    void tenantResolvesItsOwnServerById() {
        assertThat(dispatcher.resolveReceiveById(TENANT_SERVER).getId()).isEqualTo(TENANT_SERVER);
    }

    @Test
    void thePlatformResolvesOnlyItsOwnServers() {
        assertThat(dispatcher.resolvePlatformReceive().getId()).isEqualTo(PLATFORM_SERVER);
        assertThat(dispatcher.resolvePlatformReceiveById(PLATFORM_SERVER).getId()).isEqualTo(PLATFORM_SERVER);
        assertThrows(BusinessException.class, () -> dispatcher.resolvePlatformReceiveById(TENANT_SERVER));
        verify(tenantServers, never()).getById(any());
        verify(tenantServers, never()).findDefault();
    }
}
