package io.softa.framework.web.filter.context;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.web.enums.IdentifyType;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityResolverTest {

    private static IdentityResolver resolver(String contextPath) {
        IdentityResolver resolver = new IdentityResolver();
        ReflectionTestUtils.setField(resolver, "contextPath", contextPath);
        ReflectionTestUtils.setField(resolver, "additionalAnonymousPatternsRaw", "");
        ReflectionTestUtils.invokeMethod(resolver, "initPatterns");
        return resolver;
    }

    @Test
    void modelRpcIsAServiceChannelThatNeverBindsAUserSession() {
        assertThat(resolver("").getIdentifyRequired("/rpc/Employee/searchList")).isEqualTo(IdentifyType.INTERNAL);
        assertThat(resolver("/api/app").getIdentifyRequired("/api/app/rpc/Employee/searchList"))
                .isEqualTo(IdentifyType.INTERNAL);
    }

    @Test
    void ordinaryPathsStillRequireAUser() {
        assertThat(resolver("").getIdentifyRequired("/Employee/searchList")).isEqualTo(IdentifyType.USER);
        assertThat(resolver("").getIdentifyRequired("/rpcx/Employee")).isEqualTo(IdentifyType.USER);
    }
}
