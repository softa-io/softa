package io.softa.starter.message.mail.support;

import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.mail.enums.MailServerOwner;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a send goes out ({@link MailServerDispatcher}): a {@code TENANT} send through the tenant's own
 * default or, when it has none, relayed through the platform's; a {@code PLATFORM} send through the
 * platform's only, never reading the tenant's table or cache; a by-id lookup only within the named
 * owner's table, and a tenant only ever reaching its own servers.
 */
class MailServerDispatcherScopeTest {

    private MailServerDispatcher dispatcher;
    private MailSendServerConfigService sendConfigService;
    private PlatformMailSendServerService platformSendService;
    private MailConfigCache configCache;
    private PlatformMailConfigCache platformConfigCache;
    private TenantMailHostGuard hostGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dispatcher = new MailServerDispatcher();
        sendConfigService = mock(MailSendServerConfigService.class);
        platformSendService = mock(PlatformMailSendServerService.class);
        configCache = mock(MailConfigCache.class);
        platformConfigCache = mock(PlatformMailConfigCache.class);
        hostGuard = mock(TenantMailHostGuard.class);
        ReflectionTestUtils.setField(dispatcher, "sendConfigService", sendConfigService);
        ReflectionTestUtils.setField(dispatcher, "platformSendService", platformSendService);
        ReflectionTestUtils.setField(dispatcher, "configCache", configCache);
        ReflectionTestUtils.setField(dispatcher, "platformConfigCache", platformConfigCache);
        ReflectionTestUtils.setField(dispatcher, "hostGuard", hostGuard);
        // Pass-through caches: exercise the loaders supplied by the dispatcher.
        when(configCache.getDefault(anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<MailSendServerConfig>) inv.getArgument(1)).get());
        when(platformConfigCache.getDefault(anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<PlatformMailSendServer>) inv.getArgument(1)).get());
        when(configCache.getById(any(), any()))
                .thenAnswer(inv -> ((Supplier<MailSendServerConfig>) inv.getArgument(1)).get());
        when(platformConfigCache.getById(any(), any()))
                .thenAnswer(inv -> ((Supplier<PlatformMailSendServer>) inv.getArgument(1)).get());
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private static MailSendServerConfig tenantServer(long id, long tenantId) {
        MailSendServerConfig config = new MailSendServerConfig();
        config.setId(id);
        config.setTenantId(tenantId);
        config.setHost("smtp.tenant.example.com");
        return config;
    }

    private static PlatformMailSendServer platformServer(long id) {
        PlatformMailSendServer config = new PlatformMailSendServer();
        config.setId(id);
        return config;
    }

    private static <T> T asTenant(long tenantId, Supplier<T> action) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        return ContextHolder.callWith(ctx, action::get);
    }

    @Test
    void tenantScopePrefersTheTenantDefault() {
        when(sendConfigService.findDefault()).thenReturn(Optional.of(tenantServer(1L, 5L)));

        MailServerDispatcher.SendServer server = asTenant(5L, () -> dispatcher.resolveSend(MessageScope.TENANT));

        Assertions.assertEquals(1L, server.config().getId());
        Assertions.assertEquals(MailServerOwner.TENANT, server.owner());
        verify(platformSendService, never()).findDefault();
        verify(hostGuard).requirePublicBeforeConnecting("smtp.tenant.example.com");
    }

    @Test
    void tenantScopeWithoutAServerOfItsOwnIsRelayedThroughThePlatform() {
        when(sendConfigService.findDefault()).thenReturn(Optional.empty());
        when(platformSendService.findDefault()).thenReturn(Optional.of(platformServer(2L)));

        MailServerDispatcher.SendServer server = asTenant(5L, () -> dispatcher.resolveSend(MessageScope.TENANT));

        Assertions.assertEquals(2L, server.config().getId());
        Assertions.assertEquals(MailServerOwner.PLATFORM, server.owner());
    }

    @Test
    void platformScopeNeverConsultsTheTenantsServers() {
        when(platformSendService.findDefault()).thenReturn(Optional.of(platformServer(2L)));

        MailServerDispatcher.SendServer server = asTenant(5L, () -> dispatcher.resolveSend(MessageScope.PLATFORM));

        Assertions.assertEquals(MailServerOwner.PLATFORM, server.owner());
        verify(sendConfigService, never()).findDefault();
        // ...and never reads or writes the tenant's default cache entry.
        verify(configCache, never()).getDefault(anyLong(), any());
    }

    @Test
    void platformScopeWithNoPlatformDefaultFailsLoud() {
        when(platformSendService.findDefault()).thenReturn(Optional.empty());

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> asTenant(5L, () -> dispatcher.resolveSend(MessageScope.PLATFORM)));
        Assertions.assertTrue(ex.getMessage().contains("platform"));
    }

    @Test
    void withMultiTenancyOffPlatformScopeTakesTheDeploymentsOwnServerFirst() {
        SystemConfig.env = null;
        when(sendConfigService.findDefault()).thenReturn(Optional.of(tenantServer(1L, 0L)));

        Assertions.assertEquals(1L, dispatcher.resolveSend(MessageScope.PLATFORM).config().getId());
    }

    @Test
    void byIdATenantReachesOnlyItsOwnServer_aWarmCacheEntryOfAnotherTenantIncluded() {
        // The cache is keyed by id alone: tenant 6's server, cached by tenant 6's sends, must not
        // serve tenant 5 pinning the same id.
        doReturn(tenantServer(9L, 6L)).when(configCache).getById(eq(9L), any());

        Assertions.assertThrows(BusinessException.class,
                () -> asTenant(5L, () -> dispatcher.resolveSendById(MailServerOwner.TENANT, 9L)));
    }

    @Test
    void byIdThePlatformsTableIsReadForAPlatformServer() {
        when(platformSendService.getById(7L)).thenReturn(Optional.of(platformServer(7L)));

        MailServerDispatcher.SendServer server =
                asTenant(5L, () -> dispatcher.resolveSendById(MailServerOwner.PLATFORM, 7L));

        Assertions.assertEquals(7L, server.config().getId());
        verify(sendConfigService, never()).getById(any());
        verify(hostGuard, never()).requirePublicBeforeConnecting(any());
    }

    @Test
    void aTenantServerInsideTheDeploymentIsRefused() {
        when(sendConfigService.findDefault()).thenReturn(Optional.of(tenantServer(1L, 5L)));
        doThrow(new BusinessException("resolves to 10.0.0.5, an address inside the deployment"))
                .when(hostGuard).requirePublicBeforeConnecting("smtp.tenant.example.com");

        Assertions.assertThrows(BusinessException.class,
                () -> asTenant(5L, () -> dispatcher.resolveSend(MessageScope.TENANT)));
    }
}
