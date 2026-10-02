package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全响应头的**下发可达性**门禁（静态，跨运行时）。第六十九批补上这块**零覆盖**的承诺。
 *
 * <p>为什么需要它：4 条安全响应头（nosniff / DENY / Referrer-Policy / CSP Report-Only）是面向
 * 公网的安全承诺，此前**全套测试没有任何一条提到它** —— 既没有断言片段内容完整，也没有断言
 * 「每条对外配置都真的把它 include 进去了」。「容器版由外层 edge 兜住」更是只写在注释里的**假设**。
 *
 * <p>三条失败模式都属「静默失效、测试全谱看不见」：
 * <ol>
 *   <li><b>片段被删掉一条头</b> —— 少一层防护，没有任何信号；</li>
 *   <li><b>nginx 继承陷阱</b>：某个 {@code location} 内新加了自己的 {@code add_header}
 *       （例如 {@code Cache-Control}），server 级的全部 {@code add_header} 对**该 location 整体失效**
 *       —— {@code nginx -t} 照样通过、探针也看不见（探针只看进程级/端口级事实）；</li>
 *   <li><b>镜像内 include 了镜像里不存在的文件</b>：如果有人为了「消除 web/nginx.conf 与
 *       deploy/nginx/web.conf 的不对称」而在 {@code web/nginx.conf} 里补上
 *       {@code include snippets/security-headers.conf;}，镜像**构建照样成功**（只是 COPY 了个文件），
 *       但容器**启动时 nginx 直接退出**（{@code open() "/etc/nginx/snippets/..." failed}）——
 *       构建期与全部自动化都看不见。见 {@link #imageConfigMustNotIncludeFilesMissingFromImage()}。</li>
 * </ol>
 *
 * <p>读取经 {@link SourceText}（先抹白注释）—— 否则注释里出现的 {@code location} / {@code add_header}
 * 字样（本项目注释写得很多）会把块解析带偏。
 */
class SecurityHeaderDeliveryContractTest {

    /** 面向公网、必须下发安全头的 nginx 配置（宿主机直连版 / 容器 web 版 / 容器 edge 版 / TLS 测试入口模板）。 */
    private static final List<String> CONFIGS = List.of(
            "deploy/nginx/host.conf",
            "deploy/nginx/web.conf",
            "deploy/nginx/edge.conf",
            "deploy/nginx/edge-ip-test.conf.template");

    private static final String SNIPPET_PATH = "deploy/nginx/security-headers.conf";
    private static final String SNIPPET_INCLUDE = "include snippets/security-headers.conf";

    /** 片段必须逐条下发的 4 条头（`always` 保证错误响应也带）。 */
    private static final List<String> REQUIRED_HEADERS = List.of(
            "add_header X-Content-Type-Options \"nosniff\" always",
            "add_header X-Frame-Options \"DENY\" always",
            "add_header Referrer-Policy \"strict-origin-when-cross-origin\" always",
            "add_header Content-Security-Policy-Report-Only");

    private static final Pattern ADD_HEADER_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*add_header\\b");
    private static final Pattern SNIPPET_INCLUDE_DIRECTIVE =
            Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(SNIPPET_INCLUDE) + "\\s*;");
    private static final Pattern ANY_SNIPPET_INCLUDE =
            Pattern.compile("(?m)^[ \\t]*include\\s+(snippets/[\\w.-]+)\\s*;");

    @Test
    @DisplayName("片段本身完整：4 条头一条不少，且都带 always")
    void snippetCarriesAllFourHeaders() throws Exception {
        String snippet = read(SNIPPET_PATH);

        for (String header : REQUIRED_HEADERS) {
            assertTrue(snippet.contains(header),
                    "安全头片段缺少「" + header + "」—— 少一条就是少一层防护，且没有任何其它测试会报警");
        }
        long withAlways = REQUIRED_HEADERS.stream().filter(h -> h.endsWith("always")).count();
        assertTrue(withAlways >= 2, "样本自身失效：应有多条头带 always（当前 " + withAlways + "）");
        assertTrue(CONFIGS.size() >= 4, "受检配置清单过短，本门禁会失去意义");
    }

    @Test
    @DisplayName("每份对外配置都在 server 级 include 片段（最外层入口不得漏）")
    void everyPublicConfigIncludesSnippetAtServerLevel() throws Exception {
        for (String config : CONFIGS) {
            String withoutLocations = removeLocationBodies(read(config));
            assertTrue(SNIPPET_INCLUDE_DIRECTIVE.matcher(withoutLocations).find(),
                    config + " 未在 server 级 include 安全头片段 —— 无自有 add_header 的 location"
                            + "只能靠 server 级下发，漏了就没有安全头（nginx 不会继承给漏配的层级）");
        }
    }

    @Test
    @DisplayName("继承陷阱：自带 add_header 的 location 必须再 include 一次（否则该 location 的安全头全没）")
    void locationsWithOwnAddHeaderMustReIncludeSnippet() throws Exception {
        int inspectedLocations = 0;
        for (String config : CONFIGS) {
            for (String body : locationBodies(read(config))) {
                inspectedLocations++;
                if (!ADD_HEADER_DIRECTIVE.matcher(body).find()) {
                    continue;
                }
                if (!SNIPPET_INCLUDE_DIRECTIVE.matcher(body).find()) {
                    throw new AssertionError(config + " 的某个 location 内既有自有 add_header、"
                            + "又没有再 include 安全头片段 —— nginx 的 add_header **不跨层级累加**："
                            + "该 location 一旦有自己的 add_header，server 级那 4 条安全头对它**全部失效**，"
                            + "而 nginx -t 通过、探针也看不见：\n" + body);
                }
            }
        }
        assertTrue(inspectedLocations >= 6,
                "解析到的 location 块过少（" + inspectedLocations + "），解析可能已失效（本门禁会假绿）");
    }

    @Test
    @DisplayName("镜像内配置只允许 include 镜像里真实存在的文件（否则容器启动即崩、构建与测试都看不见）")
    void imageConfigMustNotIncludeFilesMissingFromImage() throws Exception {
        // web/nginx.conf 由 web/Dockerfile COPY 进 /etc/nginx/conf.d/default.conf，
        // 构建上下文是 web/ —— 它**无法**带上 deploy/nginx/ 下的片段。
        String imageConf = read("web/nginx.conf");

        assertFalse(SNIPPET_INCLUDE_DIRECTIVE.matcher(imageConf).find(),
                "web/nginx.conf 不得 include 安全头片段：该片段不在 web/ 构建上下文里，"
                        + "镜像构建**照样成功**（只是 COPY 一个文件），但容器**启动时 nginx 会直接退出**"
                        + "（open() \"/etc/nginx/snippets/security-headers.conf\" failed）——"
                        + "这是构建期与全部自动化都看不见的失败。镜像内这层不下发安全头是**刻意的**："
                        + "容器拓扑 edge → web → api 中由最外层 edge 统一下发（见 deploy/nginx/edge.conf）");
        for (Matcher matcher = ANY_SNIPPET_INCLUDE.matcher(imageConf); matcher.find(); ) {
            throw new AssertionError("web/nginx.conf include 了镜像里不存在的 " + matcher.group(1)
                    + " —— 容器启动会失败，请改用镜像自带的 mime.types / conf.d/*.conf");
        }
    }

    @Test
    @DisplayName("每个 `include snippets/X` 都指向仓库里真实存在的文件（防拼写错：nginx -t 失败会拒 reload）")
    void everySnippetIncludeResolves() throws Exception {
        int inspected = 0;
        for (String config : CONFIGS) {
            for (Matcher matcher = ANY_SNIPPET_INCLUDE.matcher(read(config)); matcher.find(); ) {
                inspected++;
                String relative = matcher.group(1);
                Path resolved = resolve("deploy/nginx/" + relative.substring("snippets/".length()));
                assertTrue(Files.isRegularFile(resolved),
                        config + " include 了 " + relative + "，但仓库里没有 " + resolved
                                + " —— 部署时该文件会被放到 /etc/nginx/snippets/，拼错即 nginx -t 失败、reload 被拒");
            }
        }
        assertTrue(inspected >= 6, "解析到的 include 过少（" + inspected + "），解析可能已失效");
    }

    // ---------- 路径与读取（沿用本包既有惯例：从 server/ 或仓库根运行都能解析） ----------

    /** 测试从 server/ 运行、资源位于仓库根；兼容从仓库根目录运行（与 StaticAssetDeliveryContractTest 同口径）。 */
    private static Path resolve(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        return Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
    }

    private static String read(String relative) throws Exception {
        Path target = resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return SourceText.read(target);
    }

    // ---------- 极简 nginx 块解析（注释已抹白，故只需按大括号配平） ----------

    /** 取出所有 {@code location ... { ... }} 块的**正文**（不含花括号本身）。 */
    private static List<String> locationBodies(String conf) {
        List<String> bodies = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = indexOfWord(conf, "location", from);
            if (at < 0) {
                return bodies;
            }
            int open = conf.indexOf('{', at);
            int end = open < 0 ? -1 : matchingBrace(conf, open);
            if (end < 0) {
                return bodies;   // 花括号不配平：交给 nginx 自己报错，这里不猜
            }
            bodies.add(conf.substring(open + 1, end));
            from = end + 1;
        }
    }

    /** 去掉所有 location 块后的剩余文本（server 级指令就留在这里）。 */
    private static String removeLocationBodies(String conf) {
        StringBuilder out = new StringBuilder(conf);
        int from = 0;
        while (true) {
            int at = indexOfWord(out, "location", from);
            if (at < 0) {
                return out.toString();
            }
            int open = out.indexOf("{", at);
            int end = open < 0 ? -1 : matchingBrace(out, open);
            if (end < 0) {
                return out.toString();
            }
            for (int i = at; i <= end; i++) {
                out.setCharAt(i, ' ');
            }
            from = at;
        }
    }

    private static int matchingBrace(CharSequence text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int indexOfWord(CharSequence text, String word, int from) {
        for (int i = from; i + word.length() <= text.length(); i++) {
            if (!text.subSequence(i, i + word.length()).toString().equals(word)) {
                continue;
            }
            boolean beforeOk = i == 0 || !isWordChar(text.charAt(i - 1));
            boolean afterOk = i + word.length() == text.length() || !isWordChar(text.charAt(i + word.length()));
            if (beforeOk && afterOk) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
