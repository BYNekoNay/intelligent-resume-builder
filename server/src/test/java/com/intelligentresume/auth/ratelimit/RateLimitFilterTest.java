package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void rejectsTheSecondRequestWithinTheSameMinute() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 1, 1, false, 100, new ObjectMapper());
        MockHttpServletRequest first = request("203.0.113.10", null);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> ((MockHttpServletResponse) response).setStatus(204);

        filter.doFilter(first, firstResponse, chain);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.10", null), secondResponse, chain);

        assertThat(firstResponse.getStatus()).isEqualTo(204);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("42901");
    }

    @Test
    void ignoresSpoofedForwardedHeaderUnlessProxyTrustIsEnabled() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 1, 1, false, 100, new ObjectMapper());
        FilterChain chain = (request, response) -> ((MockHttpServletResponse) response).setStatus(204);

        filter.doFilter(request("203.0.113.10", "198.51.100.1"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.10", "198.51.100.2"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
