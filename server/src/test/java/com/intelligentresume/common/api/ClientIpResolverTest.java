package com.intelligentresume.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ClientIpResolver} 单元测试：信任开关两态下的解析语义。
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("不信任转发头：伪造的 X-Forwarded-For 被忽略（取 remoteAddr）")
    void untrusted_ignoresForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    @DisplayName("信任转发头：取最左值并去除空白（代理链『客户端, 代理1, 代理2』）")
    void trusted_usesLeftMostEntry() {
        ClientIpResolver resolver = new ClientIpResolver(true);
        MockHttpServletRequest request = request("172.18.0.4");
        request.addHeader("X-Forwarded-For", " 203.0.113.7 , 172.18.0.3, 172.18.0.4");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }

    @Test
    @DisplayName("信任转发头但头缺失或为空白：回退 remoteAddr")
    void trusted_fallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(true);
        MockHttpServletRequest noHeader = request("10.0.0.1");
        MockHttpServletRequest blankHeader = request("10.0.0.2");
        blankHeader.addHeader("X-Forwarded-For", "   ");

        assertEquals("10.0.0.1", resolver.resolve(noHeader));
        assertEquals("10.0.0.2", resolver.resolve(blankHeader));
    }

    @Test
    @DisplayName("remoteAddr 为空：返回占位 unknown（限流分桶键非空）")
    void missingRemoteAddr_returnsUnknown() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertEquals(ClientIpResolver.UNKNOWN, resolver.resolve(request));
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}