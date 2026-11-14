package io.softa.starter.message.mail.service.impl;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.entity.PlatformMailTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Template lookups within one tier ({@link AbstractMailTemplateServiceImpl}): {@code resolve} reads
 * enabled rows only, leaving {@code active = true} to the framework's active control; the authoring
 * reads reach disabled rows too; and nothing ever reaches into the other tier — that tier is another
 * table. The tier a send expects, and the hint when a code is in the other one, are the delivery
 * processor's ({@code MailDeliveryProcessorTest}).
 */
class MailTemplateTierServiceTest {

    @Test
    void resolveReadsEnabledRowsThroughTheFrameworksActiveFilter() {
        MailTemplateServiceImpl service = spy(new MailTemplateServiceImpl());
        doReturn(Optional.of(new MailTemplate())).when(service).searchOne(any(Filters.class));

        service.resolve("user.invitation");

        // The model is activeControl: a plain Filters read is exactly what proves the service does
        // not hand-roll the condition.
        verify(service).searchOne(any(Filters.class));
    }

    @Test
    void aMissingCodeFailsLoud() {
        PlatformMailTemplateServiceImpl service = spy(new PlatformMailTemplateServiceImpl());
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.resolve("user.password-reset"));
        Assertions.assertTrue(ex.getMessage().contains("user.password-reset"));
    }

    @Test
    void theAuthoringReadsReachDisabledRows() {
        PlatformMailTemplateServiceImpl service = spy(new PlatformMailTemplateServiceImpl());
        doReturn(Optional.of(new PlatformMailTemplate())).when(service).searchOne(any(FlexQuery.class));

        service.resolveAny("user.password-reset");
        service.findAnyByCode("user.password-reset");

        // Authoring tools must inspect a template BEFORE it is activated.
        verify(service, org.mockito.Mockito.times(2)).searchOne(argThat((FlexQuery q) ->
                q.getFilterControl().isSkipActiveControl()));
    }

    @Test
    void whetherAnyTenantHasACodeIsAskedAcrossTenants() {
        MailTemplateServiceImpl service = spy(new MailTemplateServiceImpl());
        AtomicBoolean crossTenant = new AtomicBoolean();
        doAnswer(inv -> {
            crossTenant.set(ContextHolder.getContext().isCrossTenant());
            return 1L;
        }).when(service).count(any(Filters.class));
        Context tenant = new Context();
        tenant.setTenantId(-1L);

        boolean found = ContextHolder.callWith(tenant, () -> service.anyTenantHasCode("flow.approved"));

        Assertions.assertTrue(found);
        Assertions.assertTrue(crossTenant.get());
    }
}
