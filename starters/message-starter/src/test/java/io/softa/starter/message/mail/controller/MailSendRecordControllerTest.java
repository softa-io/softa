package io.softa.starter.message.mail.controller;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.shared.dto.SendRecordIdsDTO;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The platform's send log over a table that is not multi-tenant: its retries, like its reads, refuse a
 * tenant caller in code — a tenant retries its own mail through {@code TenantMailSendRecord}.
 */
class MailSendRecordControllerTest {

    private MailSendRecordService service;
    private MailSendRecordController controller;

    @BeforeEach
    void setUp() {
        service = mock(MailSendRecordService.class);
        controller = new MailSendRecordController();
        ReflectionTestUtils.setField(controller, "service", service);
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private static <T> T as(long tenantId, Supplier<T> call) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        return ContextHolder.callWith(ctx, call::get);
    }

    @Test
    void aTenantCannotRetryThroughThePlatformsLog() {
        SendRecordIdsDTO ids = new SendRecordIdsDTO();
        ids.setIds(List.of(1L, 2L));

        assertThrows(PermissionException.class, () -> as(7L, () -> controller.retry(1L)));
        assertThrows(PermissionException.class, () -> as(7L, () -> controller.retryByIds(ids)));
        verify(service, never()).retry(any());
        verify(service, never()).retryByIds(anyList());
    }

    @Test
    void thePlatformRetriesAnyRecord() {
        as(TenantScopes.PLATFORM, () -> controller.retry(1L));

        verify(service).retry(1L);
    }
}
