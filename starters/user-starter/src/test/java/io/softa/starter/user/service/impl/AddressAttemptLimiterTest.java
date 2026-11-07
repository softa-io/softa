package io.softa.starter.user.service.impl;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.config.LoginProtectionProperties;
import io.softa.starter.user.constant.AccountErrorCodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Codes and passwords tried from one address share one hourly budget; another address has its own. */
class AddressAttemptLimiterTest {

    private final Map<String, Long> counters = new HashMap<>();
    private final LoginProtectionProperties properties = new LoginProtectionProperties();
    private final AddressAttemptLimiter limiter;

    AddressAttemptLimiterTest() {
        CacheService cache = mock(CacheService.class);
        when(cache.increment(anyString(), anyLong())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        properties.setAttemptsPerAddressPerHour(3);
        limiter = new AddressAttemptLimiter(cache, properties);
    }

    @AfterEach
    void unbind() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theBudget_isPerAddress_andRefusesOnceSpent() {
        ClientRequests.fromAddress("203.0.113.9");
        limiter.count();
        limiter.count();
        limiter.count();

        assertThatThrownBy(limiter::count).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AccountErrorCodes.TOO_MANY_REQUESTS));

        ClientRequests.fromAddress("198.51.100.7");
        limiter.count();
    }

    @Test
    void outsideARequest_nothingIsCounted() {
        limiter.count();
        assertThat(counters).isEmpty();
    }

    @Test
    void theDefaults_areGenerous_andTrustOneProxy() {
        LoginProtectionProperties defaults = new LoginProtectionProperties();
        assertThat(defaults.getTrustedProxies()).isEqualTo(1);
        assertThat(defaults.getAttemptsPerAddressPerHour()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void sendingAndCheckingACode_bothDrawOnTheAddressBudget() {
        AddressAttemptLimiter spent = mock(AddressAttemptLimiter.class);
        org.mockito.Mockito.doThrow(AccountErrorCodes.refusal(AccountErrorCodes.TOO_MANY_REQUESTS, "Too many"))
                .when(spent).count();
        VerificationCodeGuard guard = new VerificationCodeGuard(mock(CacheService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "addressLimiter", spent);

        assertThatThrownBy(() -> guard.beforeSend("+6591234567")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.verify("+6591234567", "123456")).isInstanceOf(BusinessException.class);
    }
}
