package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailSendServer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * One default per owner ({@link AbstractMailSendServerServiceImpl#demoteOtherDefaults}): every other
 * default of the same table is demoted — disabled rows too. Each demotion is a model-service write, so
 * the config cache follows it through {@code ConfigCacheEvictor}.
 */
class MailSendServerConfigServiceImplTest {

    private ModelService<Long> modelService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
    }

    private static MailSendServerConfig config(long id) {
        MailSendServerConfig config = new MailSendServerConfig();
        config.setId(id);
        config.setIsDefault(true);
        return config;
    }

    private MailSendServerConfigServiceImpl tenantService() {
        MailSendServerConfigServiceImpl service = spy(new MailSendServerConfigServiceImpl());
        ReflectionTestUtils.setField(service, "modelService", modelService);
        return service;
    }

    @Test
    void demoteClearsEveryOtherDefaultButKeepsTheSavedOne() {
        MailSendServerConfigServiceImpl service = tenantService();
        doReturn(List.of(config(1L), config(2L), config(3L)))
                .when(service).searchList(any(FlexQuery.class));

        service.demoteOtherDefaults(2L);

        // Disabled rows are in scope: they too must lose isDefault, else one
        // would resurface as a second default when it is re-activated.
        verify(service).searchList(argThat((FlexQuery q) ->
                q.getFilterControl().isSkipActiveControl()));
        verify(modelService, times(2)).updateOne(eq("MailSendServerConfig"), anyMap());
        verify(modelService).updateOne("MailSendServerConfig", Map.of("id", 1L, "isDefault", false));
        verify(modelService).updateOne("MailSendServerConfig", Map.of("id", 3L, "isDefault", false));
    }

    @Test
    void demoteIsNoOpWhenTheSavedRowIsTheOnlyDefault() {
        MailSendServerConfigServiceImpl service = tenantService();
        doReturn(List.of(config(2L))).when(service).searchList(any(FlexQuery.class));

        service.demoteOtherDefaults(2L);

        verify(modelService, never()).updateOne(anyString(), anyMap());
    }

    @Test
    void thePlatformsDefaultIsDemotedInItsOwnTable() {
        PlatformMailSendServerServiceImpl service = spy(new PlatformMailSendServerServiceImpl());
        ReflectionTestUtils.setField(service, "modelService", modelService);
        PlatformMailSendServer other = new PlatformMailSendServer();
        other.setId(8L);
        other.setIsDefault(true);
        doReturn(List.of(other)).when(service).searchList(any(FlexQuery.class));

        service.demoteOtherDefaults(9L);

        verify(modelService).updateOne("PlatformMailSendServer", Map.of("id", 8L, "isDefault", false));
    }
}
