package io.softa.starter.user.service.impl;

import java.util.List;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.service.CountryRegionService;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.service.UserSecurityLogService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Field-injected collaborators of the invitation service that its tests are not about: the mobile
 * formatter (with Singapore and China as the only countries) and the security log.
 */
final class InvitationServiceTestSupport {

    private InvitationServiceTestSupport() {
    }

    static JoinMobileFormat mobileFormat() {
        CountryRegionService countries = mock(CountryRegionService.class);
        when(countries.searchList()).thenReturn(List.of(country("SG", "Singapore", "65"), country("CN", "China", "86")));
        return new JoinMobileFormat(countries);
    }

    private static CountryRegion country(String id, String name, String dialCode) {
        CountryRegion country = new CountryRegion();
        country.setId(id);
        country.setName(name);
        country.setDialCode(dialCode);
        return country;
    }

    static UserSecurityLogService quiet(UserInvitationServiceImpl service) {
        UserSecurityLogService securityLog = mock(UserSecurityLogService.class);
        when(securityLog.entry(any(), any(), any(), any())).thenAnswer(invocation -> {
            UserSecurityLog row = new UserSecurityLog();
            row.setAction(invocation.getArgument(0));
            return row;
        });
        ReflectionTestUtils.setField(service, "mobileFormat", mobileFormat());
        ReflectionTestUtils.setField(service, "securityLog", securityLog);
        ReflectionTestUtils.setField(service, "sessionService", mock(io.softa.starter.user.service.UserSessionService.class));
        return securityLog;
    }
}
