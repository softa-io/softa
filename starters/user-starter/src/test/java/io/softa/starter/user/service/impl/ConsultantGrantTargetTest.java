package io.softa.starter.user.service.impl;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.entity.ConsultantAuthorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which companies a consultant may be authorized into.
 *
 * <p>The screen's picker lists what the operator can choose, but it is a convenience: the endpoint
 * takes whatever id it is handed, so the rule about which ids are companies at all belongs on the
 * write path. Everything here asserts the refusal happens before anything is written — a grant that
 * is rejected must not have minted a membership on its way to being rejected.
 */
class ConsultantGrantTargetTest {

    private ConsultantServiceImpl service;
    private ConsultantAuthorizationService authorizationService;
    private TenantInfoService tenantInfoService;

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        authorizationService = mock(ConsultantAuthorizationService.class);
        tenantInfoService = mock(TenantInfoService.class);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(service, "tenantInfoService", tenantInfoService);
    }

    /**
     * The platform's own tier is not a customer.
     *
     * <p>It holds the operator's console and the seeded reference data; a consultant admitted into
     * it would hold full data access over the platform itself rather than over a company that asked
     * for help. And -1 is exactly the id somebody reaches for by hand when the picker will not offer
     * it, which is why the picker alone is not the guard.
     */
    @Test
    void theCompanyCannotBeThePlatformTier() {
        when(tenantInfoService.getTenantName(BaseConstant.PLATFORM_TENANT_ID)).thenReturn("Platform");

        assertThatThrownBy(() -> service.replaceAuthorizations(7L, List.of(grant(BaseConstant.PLATFORM_TENANT_ID))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a company");

        // Refused before the reconciliation runs, so nothing was minted for a grant that is not
        // going to exist. The name lookup answering "Platform" is the point: the tenant row is real,
        // which is why existence alone cannot be the test.
        verifyNoInteractions(authorizationService);
    }

    @Test
    void anOrdinaryCompanyIsStillAccepted() {
        when(tenantInfoService.getTenantName(10L)).thenReturn("Acme");
        when(authorizationService.searchList(org.mockito.ArgumentMatchers.any(Filters.class)))
                .thenReturn(List.of());

        // Reaches the reconciliation rather than being turned away at the door. It fails later, on
        // the minting this test does not wire up — what is pinned is that validation let it past.
        assertThatThrownBy(() -> service.replaceAuthorizations(7L, List.of(grant(10L))))
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    void aGrantWithNoCompanyIsRefused() {
        assertThatThrownBy(() -> service.replaceAuthorizations(7L, List.of(grant(null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("needs a company");
        verifyNoInteractions(authorizationService);
    }

    private static ConsultantAuthorization grant(Long tenantId) {
        ConsultantAuthorization a = new ConsultantAuthorization();
        a.setTenantId(tenantId);
        return a;
    }
}
