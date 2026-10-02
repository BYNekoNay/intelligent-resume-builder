package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端 API 契约 ← → 后端控制器 门禁（静态）（第五十六批）。
 *
 * <p>背景：前端一旦调用一个后端不存在的 `(method, path)`，用户会直接看到 404/405 ——
 * 现有 e2e（Playwright）只覆盖已实现流程，新增的前端调用若无对应 e2e 就无人拦截；
 * 而没有 e2e 的小改动（改路径、改动词）同样会静默埋雷。
 * 第五十六批对账 93 个前端调用与 95 个后端端点：**双向一致、零缺陷**，故把结论固化为门禁防漂移。
 *
 * <p>断言只取**单向**：前端调用的每个 `(method, path)` 必须能在后端控制器里找到。
 * 反向（后端有、前端未调用）**不断言** —— 运维端点（如 `/api/system/health/detail`）与
 * 暂无 UI 入口的端点（如 `/api/auth/logout-all`）是合理存在的。
 *
 * <p>解析要点（本次踩到两次误报，已固化）：
 * <ul>
 *   <li>路径变量统一归一：前端 `${expr}` 与后端 `{var}` 都折叠为 `{}`；</li>
 *   <li>前端的 HTTP 动词**不能**靠「verb 与路径之间的字符」匹配 —— 泛型里可能含括号甚至
 *       引号（如 {@code apiClient.post<ApiResponse<import('./ai').AiTask>>(...)}），
 *       改为「先定位路径字面量，再向前回溯最近的 verb」（{@code .get(} / {@code .post<} 等）。</li>
 * </ul>
 */
class FrontendApiContractTest {

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");

    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"[^)]*\\))?");

    /** {@code @RequestMapping(method = RequestMethod.X, value = "...")} 形式。 */
    private static final Pattern REQUEST_MAPPING_WITH_METHOD = Pattern.compile(
            "@RequestMapping\\s*\\([^)]*?RequestMethod\\.(\\w+)[^)]*?\"([^\"]+)\"[^)]*\\)");

    private static final Pattern FRONT_PATH = Pattern.compile("[`'\"](/api/[^`'\"]+)[`'\"]");

    /** 动词与调用括号之间允许泛型：`.get(` / `.post<` / `.delete<`。 */
    private static final Pattern FRONT_VERB = Pattern.compile("\\.(get|post|put|patch|delete)\\s*[<(]");

    @Test
    @DisplayName("前端 /api 调用的 (method,path) 在后端控制器中存在（否则用户可见 404/405）")
    void frontendCallsExistInBackend() throws Exception {
        Set<String> backend = backendEndpoints();
        Map<String, Set<String>> frontend = frontendCalls();

        assertTrue(backend.size() >= 80,
                "解析到的后端端点数过少（" + backend.size() + "），门禁可能未生效");
        assertTrue(frontend.size() >= 80,
                "解析到的前端调用数过少（" + frontend.size() + "），门禁可能失效"
                        + "（前端 API 写法变化？见本类注释的解析要点）");

        Set<String> backendPaths = new LinkedHashSet<>();
        for (String endpoint : backend) {
            backendPaths.add(endpoint.substring(endpoint.indexOf(' ') + 1));
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : new TreeMap<>(frontend).entrySet()) {
            if (!backend.contains(entry.getKey())) {
                String path = entry.getKey().substring(entry.getKey().indexOf(' ') + 1);
                String hint = backendPaths.contains(path)
                        ? "（路径存在但动词不同 → 405）"
                        : "（路径不存在 → 404）";
                missing.add("  " + entry.getKey() + hint + "  <- " + String.join(", ", entry.getValue()));
            }
        }

        assertTrue(missing.isEmpty(),
                "以下前端调用在后端控制器中找不到对应的 (method, path) —— 运行时会返回 404（路径不存在）"
                        + "或 405（路径存在但动词不同）。请修正前端 api 层，或补后端端点：\n"
                        + String.join("\n", missing));
    }

    /** 后端控制器的 `"METHOD /path"` 集合（路径变量归一为 `{}`）。 */
    private Set<String> backendEndpoints() throws Exception {
        Set<String> endpoints = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(repoFile("server/src/main/java"))) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String source = SourceText.read(file);
                Matcher classMatcher = CLASS_MAPPING.matcher(source);
                String base = classMatcher.find() ? classMatcher.group(1) : "";

                Matcher methodMatcher = METHOD_MAPPING.matcher(source);
                while (methodMatcher.find()) {
                    String verb = methodMatcher.group(1).toUpperCase();
                    endpoints.add(verb + " " + normalize(base + (methodMatcher.group(2) == null ? "" : methodMatcher.group(2))));
                }
                Matcher explicit = REQUEST_MAPPING_WITH_METHOD.matcher(source);
                while (explicit.find()) {
                    endpoints.add(explicit.group(1).toUpperCase() + " " + normalize(base + explicit.group(2)));
                }
            }
        }
        assertTrue(!endpoints.isEmpty(), "未解析到任何后端端点 —— 控制器注解形态可能已变化");
        return endpoints;
    }

    /** 前端 `web/src/api/*.ts` 的 `"METHOD /path"` → 出现它的文件集合。 */
    private Map<String, Set<String>> frontendCalls() throws Exception {
        Map<String, Set<String>> calls = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(repoFile("web/src/api"))) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".ts")).toList()) {
                String source = SourceText.read(file);
                String name = file.getFileName().toString();
                Matcher matcher = FRONT_PATH.matcher(source);
                while (matcher.find()) {
                    // 先定位路径字面量，再向前回溯最近的 HTTP 动词（泛型里可能出现括号/引号）
                    String before = source.substring(Math.max(0, matcher.start() - 300), matcher.start());
                    Matcher verbMatcher = FRONT_VERB.matcher(before);
                    String verb = null;
                    while (verbMatcher.find()) {
                        verb = verbMatcher.group(1).toUpperCase();
                    }
                    if (verb == null) continue;
                    calls.computeIfAbsent(verb + " " + normalize(matcher.group(1)), key -> new LinkedHashSet<>()).add(name);
                }
            }
        }
        return calls;
    }

    /** 前端模板变量 `${expr}` 与后端路径变量 `{var}` 统一为 `{}`；折叠重复斜杠、去尾斜杠。 */
    private String normalize(String path) {
        String normalized = path.replaceAll("\\$\\{[^}]*\\}", "{}")
                .replaceAll("\\{[^}]*\\}", "{}")
                .replaceAll("/+", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件/目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
