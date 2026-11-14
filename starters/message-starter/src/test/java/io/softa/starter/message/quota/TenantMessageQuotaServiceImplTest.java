package io.softa.starter.message.quota;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.quota.entity.TenantMessageQuota;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.quota.service.impl.TenantMessageQuotaServiceImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * {@link TenantMessageQuotaServiceImpl}: limit resolution falls back per
 * field to the deployment defaults. That every write is the platform's is
 * {@code TenantMessageQuotaWriteValidatorTest}'s.
 */
class TenantMessageQuotaServiceImplTest {

    private TenantMessageQuotaServiceImpl service;
    private MessageProperties properties;

    @BeforeEach
    void setUp() {
        service = spy(new TenantMessageQuotaServiceImpl());
        properties = new MessageProperties();
        ReflectionTestUtils.setField(service, "messageProperties", properties);
    }


    @Test
    void rowLimitsWinOverDefaults() {
        properties.getQuota().setMailMonthlyDefault(1000L);
        properties.getQuota().setMailDailyDefault(300L);
        TenantMessageQuota row = new TenantMessageQuota();
        row.setMailMonthlyLimit(50L);
        row.setSmsDailyLimit(7L);
        doReturn(Optional.of(row)).when(service).searchOne(any(Filters.class));

        TenantMessageQuotaService.ResolvedLimits limits = service.resolveLimits(5L);
        Assertions.assertEquals(50L, limits.mailMonthlyLimit());
        // No SMS limit on the row → falls back to the (unset) default.
        Assertions.assertNull(limits.smsMonthlyLimit());
        // Each daily cap falls back on its own too.
        Assertions.assertEquals(300L, limits.mailDailyLimit());
        Assertions.assertEquals(7L, limits.smsDailyLimit());
    }

    @Test
    void missingRowFallsBackToDeploymentDefaults() {
        properties.getQuota().setMailMonthlyDefault(1000L);
        properties.getQuota().setSmsMonthlyDefault(200L);
        properties.getQuota().setMailDailyDefault(100L);
        properties.getQuota().setSmsDailyDefault(20L);
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));

        TenantMessageQuotaService.ResolvedLimits limits = service.resolveLimits(5L);
        Assertions.assertEquals(1000L, limits.mailMonthlyLimit());
        Assertions.assertEquals(200L, limits.smsMonthlyLimit());
        Assertions.assertEquals(100L, limits.mailDailyLimit());
        Assertions.assertEquals(20L, limits.smsDailyLimit());
    }

    @Test
    void nothingConfiguredMeansUnlimited() {
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));

        TenantMessageQuotaService.ResolvedLimits limits = service.resolveLimits(5L);
        Assertions.assertNull(limits.mailMonthlyLimit());
        Assertions.assertNull(limits.smsMonthlyLimit());
    }



}
