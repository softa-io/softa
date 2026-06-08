package io.softa.framework.web.filter.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBuilderTest {

    private final ContextBuilder contextBuilder = new ContextBuilder();

    @BeforeEach
    void setUp() {
        SystemConfig config = new SystemConfig();
        config.setDebug(false);
        SystemConfig.env = config;
    }

    @ParameterizedTest
    @CsvSource({
            "param,debug,true",
            "param,debug,1",
            "header,debug,true",
            "header,debug,1",
            "header,X-Debug,true",
            "header,X-Debug,1"
    })
    void enablesDebugFromRequestParameterOrHeaders(String source, String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if ("param".equals(source)) {
            request.addParameter(name, value);
        } else {
            request.addHeader(name, value);
        }

        Context context = contextBuilder.buildAnonymousContext(request);

        assertTrue(context.isDebug());
    }

    @Test
    void keepsDebugDisabledWithoutRequestFlag() {
        Context context = contextBuilder.buildAnonymousContext(new MockHttpServletRequest());

        assertFalse(context.isDebug());
    }

    @Test
    void keepsDebugDisabledWhenRequestFlagIsFalse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BaseConstant.X_DEBUG, "false");

        Context context = contextBuilder.buildAnonymousContext(request);

        assertFalse(context.isDebug());
    }
}
