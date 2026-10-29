package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口契约文档门禁（静态）：`docs/05-接口设计说明书.md` 必须覆盖全部控制器端点。
 *
 * <p>背景：一次人工对账发现 95 个已实现端点里有 34 个未进接口说明书（其中「个人资料」
 * 「简历导入」在任何文档中都没有契约），而客户端/联调与测试都以 docs/05 为准。
 * 该门禁按控制器注解静态枚举端点，缺一个就失败——新增端点时必须同步契约文档；
 * 路径占位符名称允许不同（`{id}` / `{resumeId}` 视为同一路由）。
 *
 * <p>与其它静态门禁（i18n 键、日志隐私、导航注册表）同类：fail-closed，不静默跳过。
 */
class ApiDocCoverageGateTest {

    /** 控制器类级路由。 */
    private static final Pattern BASE_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    /** 方法级路由：兼容 `@GetMapping("/x")`、`@PostMapping(value = "/x", ...)`、`@GetMapping` 三种写法。 */
    private static final Pattern ENDPOINT_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping(?:\\s*\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?");

    @Test
    @DisplayName("docs/05 覆盖全部控制器端点（含路径占位符归一）")
    void apiDocCoversEveryControllerEndpoint() throws Exception {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path javaSources = serverRoot.resolve("src/main/java/com/intelligentresume");
        Path apiDoc = locateApiDoc(serverRoot);

        String doc = SourceText.read(apiDoc);
        List<String> missing = new ArrayList<>();
        List<Path> controllers;
        try (Stream<Path> files = Files.walk(javaSources)) {
            controllers = files.filter(path -> path.getFileName().toString().endsWith("Controller.java")).toList();
        }
        assertTrue(!controllers.isEmpty(), "未找到控制器源码，门禁路径配置有误: " + javaSources);

        for (Path controller : controllers) {
            String source = SourceText.read(controller);
            Matcher baseMatcher = BASE_MAPPING.matcher(source);
            String base = baseMatcher.find() ? baseMatcher.group(1) : "";
            Matcher mapping = ENDPOINT_MAPPING.matcher(source);
            while (mapping.find()) {
                String suffix = mapping.group(2) == null ? "" : mapping.group(2);
                String path = base + suffix;
                if (!documented(doc, path)) {
                    missing.add(mapping.group(1).toUpperCase(Locale.ROOT) + " " + path);
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "docs/05 缺少以下端点契约，请补齐后再提交（该门禁防契约文档漂移）:\n  " + String.join("\n  ", missing));
    }

    /** 控制器源码位于 server/，接口说明书位于仓库 docs/；兼容从仓库根目录运行。 */
    private Path locateApiDoc(Path serverRoot) {
        Path fromServer = serverRoot.resolve("../docs/05-接口设计说明书.md").normalize();
        if (Files.exists(fromServer)) {
            return fromServer;
        }
        Path fromRoot = serverRoot.resolve("docs/05-接口设计说明书.md");
        assertTrue(Files.exists(fromRoot), "找不到 docs/05-接口设计说明书.md（门禁必须在仓库内运行）");
        return fromRoot;
    }

    /** 占位符名称不参与比对：`/api/resumes/{id}/x` 与 `/api/resumes/{resumeId}/x` 视为同一端点。 */
    private boolean documented(String doc, String path) {
        StringBuilder pattern = new StringBuilder();
        Matcher placeholder = Pattern.compile("\\{[^}]+\\}").matcher(path);
        int cursor = 0;
        while (placeholder.find()) {
            pattern.append(Pattern.quote(path.substring(cursor, placeholder.start()))).append("\\{[^}]+\\}");
            cursor = placeholder.end();
        }
        pattern.append(Pattern.quote(path.substring(cursor)));

        Matcher hit = Pattern.compile(pattern.toString()).matcher(doc);
        while (hit.find()) {
            if (endsAtPathBoundary(doc, hit.end())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中之后必须落在路径边界上，否则说明文档里写的是**更长**的路径，本端点其实没被记录。
     *
     * <p>反例（2026-10-29 实测）：把文档里的 `/api/resume-versions/{id}/json-resume` 改成
     * `.../json-resume-XX`，原来的纯子串 `find()` 依然命中前缀 → 门禁照绿。
     * 同理会把 `/api/resumes/{id}/versions` 记成已覆盖，只要文档里出现了它的子路径
     * `/api/resumes/{id}/versions/{versionId}/archive`。两者都是「端点改名/漏写也查不出来」。
     *
     * <p>只把 ASCII 的字母数字与 `-` `_` `/` 视作「还是路径的一部分」——中文正文紧跟路径
     * （如 `…/parse接口`）不该被当成越界失败，否则门禁会被中文排版方式绊倒。
     */
    private boolean endsAtPathBoundary(String doc, int end) {
        if (end >= doc.length()) {
            return true;
        }
        char next = doc.charAt(end);
        if (next >= 128) {
            return true;
        }
        return !(Character.isLetterOrDigit(next) || next == '-' || next == '_' || next == '/');
    }
}