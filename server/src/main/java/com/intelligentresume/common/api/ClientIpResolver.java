package com.intelligentresume.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 解析：限流分桶与会话审计共用同一实现，避免两处语义漂移。
 *
 * <p>【小白导览】回答一个问题：「这个请求到底来自哪个 IP？」看似简单，实则暗坑——
 * 请求经过 nginx 等代理转发后，直连地址是代理的 IP，真实客户端 IP 藏在
 * {@code X-Forwarded-For} 请求头里；而这个头可以被客户端伪造。本类决定「信不信这个头」。
 * 改坏它：误信伪造的头 → 攻击者每换一个假 IP 就绕过限流；不信真实的头 → 所有用户
 * 共享代理 IP 的同一个限流桶，正常用户被误伤。
 *
 * <p>默认不信任 {@code X-Forwarded-For}（直连开发环境取 {@code remoteAddr}），
 * 伪造的 XFF 不参与分桶。仅当部署在可信反代之后才开启
 * {@code app.security.rate-limit.trust-forwarded-headers}（生产 compose 与
 * systemd 直连部署均已设为 true）——此时取 XFF 最左值。
 *
 * <p><b>可信链前提</b>：最外层代理必须**覆写**（而非追加）XFF 为 {@code $remote_addr}，
 * 仓库内 {@code deploy/nginx/edge.conf}、{@code host.conf}、
 * {@code edge-ip-test.conf.template} 均如此；内层 web 代理继续
 * {@code $proxy_add_x_forwarded_for} 追加，保证真实客户端仍在最左。
 * 若最外层只追加，客户端可伪造最左值绕过按 IP 限流，并把分桶表顶到
 * {@code maxBuckets} 触发 fail-closed 误伤——该契约由
 * {@code DeployProxyClientIpContractTest} 静态门禁守护。
 */
@Component
public class ClientIpResolver {

    /** 解析不出地址时的占位值（与限流分桶兼容的非空键）。 */
    static final String UNKNOWN = "unknown";

    private final boolean trustForwardedHeaders;

    public ClientIpResolver(
            @Value("${app.security.rate-limit.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    /** 返回用于分桶/审计的客户端 IP。 */
    public String resolve(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                int comma = forwardedFor.indexOf(',');
                return (comma > 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? UNKNOWN : remoteAddr;
    }
}