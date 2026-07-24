package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitFilter 单元测试。
 *
 * <p>直接调用 {@code doFilterInternal}，验证内存令牌桶的限流行为。
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // login=2/min, register=5/min, refresh=30/min
        filter = new RateLimitFilter(2, 5, 30, false, 10000, objectMapper);
    }

    @Test
    @DisplayName("第 3 次登录请求返回 429（限额 2 次/分钟）")
    void overLimit_returnsRateLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // 前 2 次放行
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次请求应放行");
        }

        // 第 3 次限流
        MockHttpServletRequest req = loginRequest("10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        assertEquals(429, resp.getStatus(), "第 3 次请求应被限流");
        assertTrue(resp.getContentAsString().contains("42901"),
                "响应体应包含错误码 42901");

        // chain 只被调用了 2 次（前 2 次放行）
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    @DisplayName("不同 IP 互不影响")
    void differentIps_independentBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // IP-A 用完 2 次配额
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus());
        }

        // IP-A 第 3 次被限流
        MockHttpServletResponse respA = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.1"), respA, chain);
        assertEquals(429, respA.getStatus(), "IP-A 应被限流");

        // IP-B 不受影响
        MockHttpServletResponse respB = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.2"), respB, chain);
        assertEquals(200, respB.getStatus(), "IP-B 不应被限流");

        verify(chain, times(3)).doFilter(any(), any());
    }

    private MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }
}
