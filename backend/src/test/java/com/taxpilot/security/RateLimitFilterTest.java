package com.taxpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    @Test
    void blocksAfterLimitPerIpAndEndpoint() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3);
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "10.0.0.1", "/api/v1/auth/login").getStatus()).isEqualTo(200);
        }
        assertThat(call(filter, "10.0.0.1", "/api/v1/auth/login").getStatus()).isEqualTo(429);
        assertThat(call(filter, "10.0.0.2", "/api/v1/auth/login").getStatus()).isEqualTo(200);
        assertThat(call(filter, "10.0.0.1", "/api/v1/clients").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String ip, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
