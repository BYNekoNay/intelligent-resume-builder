package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向代理客户端 IP 契约门禁（静态）：最外层代理覆写 X-Forwarded-For，内层代理追加。
 *
 * <p>背景：应用开启 {@code RATE_LIMIT_TRUST_FORWARDED_HEADERS}（prod profile 由
 * ProductionConfigurationValidator 强制）后取 XFF 最左值做限流分桶与会话审计。
 * 若最外层代理用 {@code $proxy_add_x_forwarded_for} 追加，客户端提供的伪造值会留在
 * 最左（绕过按 IP 限流，并把分桶表顶到 maxBuckets 触发 fail-closed 误伤）；
 * 若内层代理改成覆写，最左值会变成边缘容器地址，所有客户端退化为共享一个分桶。
 * 两个方向都会让「按客户端 IP 限流」静默失效——该门禁防此类配置漂移。
 */
class DeployProxyClientIpContractTest {

    /** 客户端入口（最外层代理）：必须覆写，终止客户端伪造链。 */
    private static final List<String> OUTERMOST = List.of(
            "deploy/nginx/edge.conf",
            "deploy/nginx/host.conf",
            "deploy/nginx/edge-ip-test.conf.template");

    /** 内层代理（edge 之后的 web）：必须继续追加，真实客户端保持在最左。 */
    private static final List<String> INNER = List.of(
            "web/nginx.conf",
            "deploy/nginx/web.conf");

    @Test
    @DisplayName("最外层代理以 $remote_addr 覆写 X-Forwarded-For")
    void outermostProxiesOverwriteForwardedFor() throws Exception {
        for (String relative : OUTERMOST) {
            String conf = read(relative);
            assertTrue(conf.contains("proxy_set_header X-Forwarded-For $remote_addr;"),
                    relative + " 必须以 $remote_addr 覆写 X-Forwarded-For（限流分桶取最左值）");
            assertFalse(conf.contains("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"),
                    relative + " 不得追加客户端提供的 X-Forwarded-For（可被伪造绕过限流）");
        }
    }

    @Test
    @DisplayName("内层代理追加 X-Forwarded-For（$proxy_add_x_forwarded_for）")
    void innerProxiesAppendForwardedFor() throws Exception {
        for (String relative : INNER) {
            String conf = read(relative);
            assertTrue(conf.contains("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"),
                    relative + " 必须继续追加 X-Forwarded-For，否则最左值会退化为边缘地址（全站共享分桶）");
            assertFalse(conf.contains("proxy_set_header X-Forwarded-For $remote_addr;"),
                    relative + " 不得覆写 X-Forwarded-For（会丢失真实客户端）");
        }
    }

    /** 控制器源码位于 server/，部署配置位于仓库根；兼容从仓库根目录运行。 */
    private String read(String relative) throws Exception {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到部署配置: " + relative + "（门禁必须在仓库内运行）");
        return SourceText.read(target);
    }
}