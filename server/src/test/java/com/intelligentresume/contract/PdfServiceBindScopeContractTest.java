package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 服务监听范围的交付契约门禁（静态，跨运行时）。
 *
 * <p>背景（第四十批取证）：
 * <ul>
 *   <li>{@code docs/08-部署与运维说明书.md} §3.3 要求「PDF 服务只监听私有网络接口（127.0.0.1 或内网 IP），
 *       不直接暴露给浏览器」，§10.1 要求「MySQL、PDF 服务和管理端口不得暴露公网」；</li>
 *   <li>直连部署路径（{@code deploy/systemd/intelligent-resume-pdf.service}）下 PDF 服务此前调用
 *       {@code app.listen(port)} **不带 host**，Node 因此绑定所有接口：本机实测
 *       {@code ::}:3011 处于 Listen，且从非回环地址（192.168.x.x）请求 {@code /render} 返回 401
 *       ——即同网段可达，公网可达与否仅取决于云安全组；</li>
 *   <li>同一交付链路的 api 单元早已因同类问题（{@code .env} 里的 {@code SERVER_ADDRESS} 无占位符
 *       被静默忽略 → 监听 {@code *:8080}）改为在单元内注入 {@code Environment=SERVER_ADDRESS=127.0.0.1}，
 *       两条单元的口径应当一致。</li>
 * </ul>
 *
 * <p>反向约束：容器路径**必须**保持绑定所有接口——私有网络内 API 容器经服务名
 * （{@code deploy/production.env.example} 的 {@code PDF_SERVICE_BASE_URL=http://pdf-service:3001}）
 * 访问 PDF 服务，把容器也收敛到回环会让导出整体不可用。因此本门禁既要求
 * 「直连路径收敛」，也要求「容器路径不得收敛」。
 */
class PdfServiceBindScopeContractTest {

    private static final String SERVER_JS = "pdf-service/src/server.js";
    private static final String PDF_UNIT = "deploy/systemd/intelligent-resume-pdf.service";
    private static final String API_UNIT = "deploy/systemd/intelligent-resume-api.service";
    private static final String PROD_COMPOSE = "deploy/docker-compose.prod.yml";
    private static final String IP_TEST_COMPOSE = "deploy/docker-compose.ip-test.yml";
    private static final String PROD_ENV_EXAMPLE = "deploy/production.env.example";

    /** 回环取值（任一出现即为「只监听本机」，在容器拓扑里会让 API 连不上）。 */
    private static final String[] LOOPBACK_VALUES = {"127.0.0.1", "localhost", "::1", "[::1]"};

    @Test
    @DisplayName("PDF 服务的监听地址必须可配置，且启动日志报出真实绑定地址")
    void pdfServiceHonoursConfiguredBindHost() throws Exception {
        String source = read(SERVER_JS);

        assertTrue(source.contains("process.env.PDF_SERVICE_HOST"),
                SERVER_JS + " 必须从 PDF_SERVICE_HOST 解析监听地址：缺省（不指定 host）时 Node 绑定所有接口，"
                        + "直连部署路径下 3001 对同网段可达，是否公网可达只取决于云安全组");
        assertTrue(source.matches("(?s).*app\\.listen\\(port, host, onListening\\).*"),
                SERVER_JS + " 必须把解析出的 host 传给 app.listen（形如 app.listen(port, host, onListening)）；"
                        + "仅读取环境变量而不传给 listen 等于没生效");
        assertFalse(source.matches("(?s).*app\\.listen\\(port, \\(\\).*"),
                SERVER_JS + " 不得保留「无条件不指定 host」的 listen 形式（会让上述配置形同虚设）");
        assertTrue(source.contains("PDF_SERVICE_HOST must not contain whitespace"),
                SERVER_JS + " 对含空白的监听地址必须 fail-closed 启动失败：这类取值是被截断/拼接坏掉的配置，"
                        + "绑定结果不可预期，不能留下「看起来启动了但绑错接口」的中间态");
    }

    @Test
    @DisplayName("直连部署的 PDF 单元必须把监听地址收敛到回环（与 api 单元的 SERVER_ADDRESS 同口径）")
    void systemdPdfUnitBindsLoopback() throws Exception {
        String pdfUnit = read(PDF_UNIT);

        assertTrue(pdfUnit.contains("Environment=PDF_SERVICE_HOST=127.0.0.1"),
                PDF_UNIT + " 必须注入 Environment=PDF_SERVICE_HOST=127.0.0.1（真实环境变量形式，与 api 单元的 "
                        + "Environment=SERVER_ADDRESS 同理）：只写进 .env 会被 --env-file 的优先级规则与占位符消费规则"
                        + "静默忽略，而该单元与 " + API_UNIT + " 对同一类问题（内部服务暴露到所有接口）必须给出"
                        + "一致的处理");

        // 顺带守住 api 单元既有的同口径取值，避免「修了 pdf 又回退 api」。
        assertTrue(read(API_UNIT).contains("Environment=SERVER_ADDRESS=127.0.0.1"),
                API_UNIT + " 应保持 Environment=SERVER_ADDRESS=127.0.0.1");
    }

    @Test
    @DisplayName("容器路径不得把 PDF 服务收敛到回环（私有网络内 API 需经 pdf-service:3001 访问）")
    void containerPathKeepsAllInterfaces() throws Exception {
        String baseUrl = read(PROD_ENV_EXAMPLE);
        assertTrue(baseUrl.replace(" ", "").contains("PDF_SERVICE_BASE_URL=http://pdf-service:3001"),
                PROD_ENV_EXAMPLE + " 的 PDF_SERVICE_BASE_URL 应指向 compose 服务名（容器拓扑下 API 不是通过回环访问 PDF 服务），"
                        + "该事实是本门禁反向约束的依据");

        Map<String, String> problems = new TreeMap<>();
        for (String compose : new String[]{PROD_COMPOSE, IP_TEST_COMPOSE}) {
            Map<String, Object> environment = asMap(asMap(services(compose).get("pdf-service")).get("environment"));
            Object configured = environment.get("PDF_SERVICE_HOST");
            if (configured == null) {
                continue;
            }
            String value = String.valueOf(configured).trim();
            for (String loopback : LOOPBACK_VALUES) {
                if (value.equals(loopback)) {
                    problems.put(compose, "PDF_SERVICE_HOST=" + value
                            + "：容器必须绑定所有接口，私有网络内的 API 容器经服务名访问本服务，"
                            + "收敛到回环会让 PDF 导出整体不可用");
                }
            }
        }
        assertTrue(problems.isEmpty(), "容器交付路径的监听范围被错误收敛：\n" + problems);
    }

    @Test
    @DisplayName("PDF 单元仍以生产模式启动（监听收敛不得顺带丢掉令牌强校验）")
    void pdfUnitKeepsProductionMode() throws Exception {
        String pdfUnit = read(PDF_UNIT);
        assertTrue(pdfUnit.contains("Environment=NODE_ENV=production"),
                PDF_UNIT + " 必须保持 Environment=NODE_ENV=production：该值是 PDF_SERVICE_TOKEN "
                        + "≥32 位非默认值强校验的唯一开关，缺失会退回开发默认令牌");
    }

    private Map<String, Object> services(String compose) throws Exception {
        Object services = loadYaml(compose).get("services");
        assertTrue(services instanceof Map, compose + " 缺少 services 段");
        return asMap(services);
    }

    private Map<String, Object> loadYaml(String relative) throws Exception {
        try (InputStream input = Files.newInputStream(repoFile(relative))) {
            Object loaded = new Yaml().load(input);
            assertTrue(loaded instanceof Map, relative + " 顶层应为映射");
            return asMap(loaded);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private String read(String relative) throws Exception {
        return Files.readString(repoFile(relative), StandardCharsets.UTF_8);
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
