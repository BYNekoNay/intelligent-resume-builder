package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传路径契约门禁（静态，跨运行时）：nginx 各层体积上限的关系，以及前端上传请求超时与服务端解析预算的关系。
 *
 * <p>背景（第二十八批取证）：生产容器拓扑为 {@code edge → web → api}，三层各自有独立的体积闸——
 * <ul>
 *   <li>{@code edge}（{@code deploy/nginx/edge.conf}）声明 {@code client_max_body_size 5m}；</li>
 *   <li>{@code web}（镜像内实际打包 {@code web/nginx.conf}）**未声明**该指令 → 回退到 nginx 内置默认
 *       {@code 1m}，成为整条链真正的绑定环节：1~5MB 的简历被内层以 413（nginx 自带 HTML，非统一
 *       信封）拒绝，与 {@code docs/05} §13 承诺的 5MB 不一致。该缺陷对 CI（功能回归直连 API、
 *       不经 nginx）与本地开发（Vite 代理无体积限制）都不可见。</li>
 * </ul>
 * 数值分散在多个 nginx conf、application.yml 与前端 TS 中，故用门禁固化关系而不是靠注释。
 */
class UploadPathContractTest {

    /** 含 API 反代的 nginx 配置（这些必须显式声明体积上限，不能落回 nginx 默认 1m）。 */
    private static final String[] API_PROXY_CONFS = {
            "web/nginx.conf",
            "deploy/nginx/web.conf",
            "deploy/nginx/host.conf"
    };

    @Test
    @DisplayName("含 /api/ 反代的 nginx 配置必须显式声明 client_max_body_size（不落回默认 1m）")
    void apiProxyConfigsDeclareBodyLimit() throws Exception {
        for (String conf : API_PROXY_CONFS) {
            String content = read(conf);
            assertTrue(content.contains("location /api/"), conf + " 应含 /api/ 反代（否则本门禁清单需更新）");
            assertTrue(BODY_LIMIT.matcher(content).find(),
                    conf + " 未声明 client_max_body_size：nginx 默认 1m 会成为绑定上限，"
                            + "1MB 以上的上传在该层被拒（且返回 nginx 自带 HTML 而非统一信封）");
        }
    }

    @Test
    @DisplayName("内层上限 ≥ 外层上限：外层承诺的上限不会被内层悄悄收紧（容器 edge→web 与 ip-test 覆盖层）")
    void innerLayerAllowsAtLeastOuterLayer() throws Exception {
        long web = bodyLimitBytes(read("web/nginx.conf"), "web/nginx.conf");
        for (String outer : new String[]{"deploy/nginx/edge.conf", "deploy/nginx/edge-ip-test.conf.template"}) {
            long outerLimit = bodyLimitBytes(read(outer), outer);
            assertTrue(web >= outerLimit,
                    "内层 web（" + web + " 字节）必须 ≥ 外层 " + outer + "（" + outerLimit + " 字节）："
                            + "否则外层放行的请求会在内层被拒，客户端收到的是未文档化的内层错误");
        }
    }

    @Test
    @DisplayName("生产各层上限 ≥ 应用「整请求」上限：docs/05 §13 承诺的 5MB 文件必须端到端可达")
    void proxyLayersAllowDocumentedUploadSize() throws Exception {
        String yaml = read("server/src/main/resources/application.yml");
        long fileLimit = yamlMegabytes(yaml, "max-file-size");
        long requestLimit = yamlMegabytes(yaml, "max-request-size");

        // 应用刻意给整请求留出高于单文件上限的余量（multipart 边界/头/其它 part）。
        // nginx 的 client_max_body_size 限制的是**整请求体**，若按单文件上限对齐，
        // 恰好 5MB 的文件会因这几百字节开销被 nginx 拒——接口文档承诺的边界不可达。
        assertTrue(requestLimit > fileLimit,
                "application.yml 的 max-request-size（" + requestLimit + " 字节）必须大于 max-file-size（"
                        + fileLimit + " 字节），为 multipart 开销留余量");

        for (String conf : new String[]{"web/nginx.conf", "deploy/nginx/edge.conf", "deploy/nginx/host.conf"}) {
            long limit = bodyLimitBytes(read(conf), conf);
            assertTrue(limit >= requestLimit,
                    conf + "（" + limit + " 字节）必须 ≥ 应用「整请求」上限（" + requestLimit
                            + " 字节 = max-request-size）：本层限制的是整个请求体（含 multipart 开销），"
                            + "按单文件上限对齐会让 5MB 的文件在边界处被本层 413 拒绝");
        }
    }

    @Test
    @DisplayName("导出下载显式放宽超时：响应体上限 10MB，全局 10s 会在慢网络下中断合法下载")
    void exportDownloadSetsExplicitTimeout() throws Exception {
        String api = read("web/src/api/export.ts");
        Matcher download = Pattern.compile("apiClient\\.get<Blob>\\(`/api/exports/files/\\$\\{id\\}`[^)]*\\)")
                .matcher(api);
        assertTrue(download.find(), "web/src/api/export.ts 应存在导出文件下载调用（否则本门禁需更新）");
        assertTrue(Pattern.compile("timeout:\\s*[\\d_]+").matcher(download.group()).find(),
                "导出下载未显式设置 timeout：会使用全局 10s，而 pdf.max-output-bytes 默认 10485760 字节"
                        + "（慢网络下合法下载被中断）");
    }

    @Test
    @DisplayName("deploy/nginx/web.conf 与镜像内 web/nginx.conf 的体积上限保持一致（两份同源副本）")
    void mirroredWebConfStaysInSync() throws Exception {
        long packaged = bodyLimitBytes(read("web/nginx.conf"), "web/nginx.conf");
        long mirror = bodyLimitBytes(read("deploy/nginx/web.conf"), "deploy/nginx/web.conf");
        assertEquals(packaged, mirror,
                "deploy/nginx/web.conf 声明为容器版同步副本，体积上限必须与 web/nginx.conf 一致");
    }

    @Test
    @DisplayName("前端上传请求超时晚于服务端解析预算：慢但成功的解析不会被客户端先判失败")
    void uploadRequestTimeoutExceedsServerBudget() throws Exception {
        long extractBudgetMs = yamlEnvDefaultMs(read("server/src/main/resources/application.yml"),
                "RESUME_IMPORT_EXTRACT_TIMEOUT_MS");
        String api = read("web/src/api/resumeImport.ts");
        Matcher matcher = Pattern.compile("timeout:\\s*([\\d_]+)").matcher(api);
        assertTrue(matcher.find(),
                "web/src/api/resumeImport.ts 的上传请求未显式设置 timeout：会使用全局 10s，"
                        + "早于服务端 " + extractBudgetMs + "ms 的解析预算（客户端先断开，服务端仍在校验）");
        long clientTimeoutMs = Long.parseLong(matcher.group(1).replace("_", ""));
        assertTrue(clientTimeoutMs > extractBudgetMs,
                "上传请求超时（" + clientTimeoutMs + "ms）必须晚于服务端解析预算（" + extractBudgetMs
                        + "ms）：内层死线先触发，客户端才不会「先断开、后成功」并把合法慢解析显示为失败");
    }

    @Test
    @DisplayName("账号导出下载超时与兄弟下载路径对齐：体量随账号增长且无上限，30s 只够 ~3.2Mbps")
    void accountExportDownloadSetsSufficientTimeout() throws Exception {
        String api = read("web/src/api/auth.ts");
        Matcher call = Pattern.compile("apiClient\\.get<string>\\('/api/auth/export'[^)]*\\)").matcher(api);
        assertTrue(call.find(), "web/src/api/auth.ts 应存在账号导出调用（否则本门禁需更新）");
        Matcher timeout = Pattern.compile("timeout:\\s*([\\d_]+)").matcher(call.group());
        assertTrue(timeout.find(), "账号导出未显式设置 timeout：会回落到全局 10s");
        long clientTimeoutMs = Long.parseLong(timeout.group(1).replace("_", ""));
        assertTrue(clientTimeoutMs >= 60_000,
                "账号导出下载超时（" + clientTimeoutMs + "ms）应 ≥ 60000ms：响应体随账号数据量线性增长"
                        + "（实测 200 条 62KB 职业资料的账号单次响应 11.92MB，且导出无体积上限），"
                        + "与同一类下载路径（PDF 导出，体上限 10MB）的 60s 对齐");
    }

    private static final Pattern BODY_LIMIT =
            Pattern.compile("client_max_body_size\\s+(\\d+)\\s*([kKmMgG]?)");

    /** 解析 nginx 配置中的 client_max_body_size（支持 k/m/g 后缀，缺省为字节）。 */
    private long bodyLimitBytes(String content, String label) {
        Matcher matcher = BODY_LIMIT.matcher(content);
        assertTrue(matcher.find(), label + " 未声明 client_max_body_size（nginx 默认仅 1m）");
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase()) {
            case "k" -> value * 1024L;
            case "m" -> value * 1024L * 1024L;
            case "g" -> value * 1024L * 1024L * 1024L;
            default -> value;
        };
    }

    /** 解析 application.yml 中形如 `max-file-size: 5MB` 的兆字节取值。 */
    private long yamlMegabytes(String yaml, String key) {
        Matcher matcher = Pattern.compile(key + ":\\s*(\\d+)MB").matcher(yaml);
        assertTrue(matcher.find(), "application.yml 未声明 " + key + " 的 MB 取值");
        return Long.parseLong(matcher.group(1)) * 1024L * 1024L;
    }

    /** 解析 application.yml 中 `${ENV_NAME:123}` 形式的毫秒默认值。 */
    private long yamlEnvDefaultMs(String yaml, String envName) {
        Matcher matcher = Pattern.compile("\\$\\{" + envName + ":(\\d+)\\}").matcher(yaml);
        assertTrue(matcher.find(), "application.yml 未声明 " + envName + " 的默认值");
        return Long.parseLong(matcher.group(1));
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
