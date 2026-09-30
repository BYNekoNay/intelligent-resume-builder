package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应压缩契约门禁（静态）：接口的大 JSON 响应必须启用压缩。
 *
 * <p>背景（第三十四批取证）：应用此前没有任何响应压缩配置。真实 HTTP 探针实测账号导出
 * （200 条 63KB 散文式职业资料）单次响应 **12.77MB**，客户端带 {@code Accept-Encoding: gzip}
 * 时服务端仍原样返回（响应既无 {@code Content-Encoding} 也无 {@code Vary: accept-encoding}）；
 * 开启后同一响应 **2.14MB**（约 6×），列表类 JSON 响应更高。
 *
 * <p>该契约只由配置承载，且**任何既有测试都不会因它缺失而变红**（MockMvc 不经过 Tomcat，
 * 压缩不会发生），故用门禁固化：配置被删/被关/json 被移出 mime 列表/min-response-size 被设为 0
 * 都会让收益静默消失。
 */
class ResponseCompressionContractTest {

    @Test
    @DisplayName("server.compression 开启且覆盖 application/json，min-response-size 不低于 1KB")
    void compressionEnabledForJsonResponses() throws Exception {
        Map<String, Object> compression = serverCompression(loadYaml("server/src/main/resources/application.yml"));

        assertTrue(Boolean.parseBoolean(String.valueOf(compression.get("enabled"))),
                "server.compression.enabled 必须为 true：关闭后大 JSON 响应原样传输"
                        + "（实测账号导出 12.77MB → 2.14MB 的收益静默消失）");

        Object mimeTypes = compression.get("mime-types");
        assertTrue(mimeTypes != null && Arrays.stream(String.valueOf(mimeTypes).split(","))
                        .map(String::trim)
                        .anyMatch("application/json"::equals),
                "server.compression.mime-types 必须包含 application/json（当前值：" + mimeTypes
                        + "）：否则接口响应（Content-Type: application/json）不会被压缩");

        Object minResponseSize = compression.get("min-response-size");
        assertTrue(minResponseSize != null,
                "server.compression.min-response-size 应显式声明（缺省 2KB）：压缩过小的响应"
                        + "反而会因压缩头与字典开销而变大");
        long minBytes = Long.parseLong(String.valueOf(minResponseSize).trim());
        assertTrue(minBytes >= 1024,
                "server.compression.min-response-size（" + minBytes + " 字节）不应低于 1KB：阈值过低会让小响应被压缩后反而更大");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serverCompression(Map<String, Object> root) {
        Object server = root.get("server");
        assertTrue(server instanceof Map, "application.yml 顶层缺少 server 段");
        Object compression = ((Map<String, Object>) server).get("compression");
        assertTrue(compression instanceof Map, "application.yml 的 server 段缺少 compression 配置");
        return (Map<String, Object>) compression;
    }

    private Map<String, Object> loadYaml(String relative) throws Exception {
        Path target = repoFile(relative);
        try (InputStream input = Files.newInputStream(target)) {
            Object loaded = new Yaml().load(input);
            assertTrue(loaded instanceof Map, relative + " 顶层应为映射");
            return (Map<String, Object>) loaded;
        }
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
