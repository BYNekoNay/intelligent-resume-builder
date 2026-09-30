package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关闭语义契约门禁（静态，跨运行时 × 交付配置）：
 * <ul>
 *   <li>API 以 graceful 停机并在上限内等待在途 HTTP 请求——容器宽限期必须大于该上限，
 *       否则 docker 会在优雅停机中途 SIGKILL（默认宽限期仅 10s）；</li>
 *   <li>pdf-service 以 drain 停机并在上限内等待 in-flight 渲染——容器宽限期同样必须大于
 *       drain 上限（第十六批已建立，本门禁一并固化防漂移）。</li>
 * </ul>
 * 数值分散在 Spring 配置、Node 服务与 compose 交付清单三处，靠注释约束不牢靠。
 */
class ShutdownContractTest {

    @Test
    @DisplayName("API 优雅停机上限被容器宽限期覆盖（宽限期 ≥ 上限 + 5s）")
    void apiGracefulShutdownIsCoveredByContainerGracePeriod() throws Exception {
        String apiYaml = read("server/src/main/resources/application.yml");
        assertTrue(apiYaml.contains("shutdown: graceful"),
                "application.yml 必须声明 server.shutdown=graceful（否则 SIGTERM 会立即断开在途请求）");

        Matcher timeout = Pattern.compile("\\$\\{SHUTDOWN_TIMEOUT:(\\d+)s\\}").matcher(apiYaml);
        assertTrue(timeout.find(), "application.yml 未声明 spring.lifecycle.timeout-per-shutdown-phase 上限");
        long gracefulSeconds = Long.parseLong(timeout.group(1));

        long apiGraceSeconds = serviceStopGrace("api");
        assertTrue(apiGraceSeconds >= gracefulSeconds + 5,
                "compose api.stop_grace_period（" + apiGraceSeconds + "s）必须 ≥ 优雅停机上限（" + gracefulSeconds
                        + "s）+ 5s 余量，否则 docker 会在优雅停机中途 SIGKILL");
    }

    @Test
    @DisplayName("pdf-service drain 上限被容器宽限期覆盖（宽限期 ≥ drain + 5s）")
    void pdfDrainIsCoveredByContainerGracePeriod() throws Exception {
        String serviceJs = read("pdf-service/src/server.js");
        Matcher drain = Pattern.compile("positiveInteger\\('PDF_SERVICE_DRAIN_TIMEOUT_MS',\\s*([\\d_]+)").matcher(serviceJs);
        assertTrue(drain.find(), "pdf-service/src/server.js 未声明 PDF_SERVICE_DRAIN_TIMEOUT_MS 默认值");
        long drainMs = Long.parseLong(drain.group(1).replace("_", ""));

        long pdfGraceSeconds = serviceStopGrace("pdf-service");
        assertTrue(pdfGraceSeconds * 1000 >= drainMs + 5000,
                "compose pdf-service.stop_grace_period（" + pdfGraceSeconds + "s）必须 ≥ drain 上限（" + drainMs
                        + "ms）+ 5s 余量，否则 docker 会在渲染中途 SIGKILL");
    }

    /** 从 compose 交付清单中取出指定服务的 stop_grace_period（秒）。 */
    private long serviceStopGrace(String service) throws Exception {
        String compose = read("deploy/docker-compose.prod.yml");
        Matcher block = Pattern.compile("(?ms)^  " + service + ":\\n(.*?)(?=^  [a-z0-9-]+:|\\Z)").matcher(compose);
        assertTrue(block.find(), "deploy/docker-compose.prod.yml 中找不到服务块：" + service);
        Matcher grace = Pattern.compile("stop_grace_period:\\s*(\\d+)s").matcher(block.group(1));
        assertTrue(grace.find(), service + " 未声明 stop_grace_period（会退回 docker 默认 10s，可能中途 SIGKILL）");
        return Long.parseLong(grace.group(1));
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private String read(String relative) throws Exception {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return Files.readString(target, StandardCharsets.UTF_8);
    }
}