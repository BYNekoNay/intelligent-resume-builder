package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端枚举 ← → 后端枚举 门禁（静态）（第五十八批）。
 *
 * <p>背景：同一组取值在前端是一份 TS 联合类型 / 常量数组，在后端是一个 Java 枚举 —— **两处手工维护**，
 * 任一处漏改就静默漂移：后端新增取值时前端类型不承认它（TS 不报错，运行期按未处理分支走），
 * 或前端多出后端不存在的取值（永远匹配不到）。第五十八批实测出 1 处：
 * {@code AiTask.taskType} 只有 8 个值、而同文件里 {@code AI_CONSENT_TASK_SCOPES} 与后端
 * {@code AiTaskType} 都有 9 个（漏 {@code RESUME_OPTIMIZE}）——**同一文件内两处清单不一致**。
 *
 * <p>修复方式不是「把漏的那个补上」，而是让前端**单一来源**：{@code AiTask.taskType} 改为从
 * {@code AI_CONSENT_TASK_SCOPES} 派生的 {@code AiTaskType} 类型，结构上不可能再漂移。
 * 本门禁则守住「前端那份清单 ↔ 后端枚举」两侧。
 */
class FrontendEnumContractTest {

    /**
     * @param backendEnum 后端枚举类名（在 {@code server/src/main/java} 下按文件名查找）
     * @param frontFile   前端文件（仓库相对路径）
     * @param frontSymbol 前端清单符号名
     * @param arrayLiteral true = {@code export const X = ['A','B'] as const}；false = {@code export type X = 'A' | 'B'}
     */
    private record Pair(String backendEnum, String frontFile, String frontSymbol, boolean arrayLiteral) { }

    private static final List<Pair> PAIRS = List.of(
            new Pair("AiTaskType", "web/src/api/ai.ts", "AI_CONSENT_TASK_SCOPES", true),
            new Pair("AiTaskStatus", "web/src/api/ai.ts", "TaskStatus", false),
            new Pair("ApplicationStatus", "web/src/api/application.ts", "ApplicationStatus", false),
            new Pair("ConfirmationStatus", "web/src/api/ai.ts", "ConfirmationStatus", false));

    private static final Pattern ENUM_CONSTANT = Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*[,;]");

    private static final Pattern STRING_LITERAL = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    @Test
    @DisplayName("后端枚举与前端同名清单的取值集合一致")
    void frontendEnumsMatchBackend() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (Pair pair : PAIRS) {
            Set<String> backend = backendEnumValues(pair.backendEnum());
            assertTrue(!backend.isEmpty(),
                    "未解析到后端枚举 " + pair.backendEnum() + " —— 枚举缺失或解析失效");
            Set<String> frontend = pair.arrayLiteral()
                    ? frontendArrayLiteral(pair.frontFile(), pair.frontSymbol())
                    : frontendUnionType(pair.frontFile(), pair.frontSymbol());
            assertTrue(!frontend.isEmpty(),
                    "未解析到前端清单 " + pair.frontSymbol() + "（" + pair.frontFile() + "）—— 解析失效或符号改名");
            compared++;
            if (!backend.equals(frontend)) {
                problems.add("  " + pair.backendEnum() + " ←→ " + pair.frontSymbol()
                        + "：仅后端有 " + difference(backend, frontend)
                        + "；仅前端有 " + difference(frontend, backend));
            }
        }

        assertTrue(compared >= 4, "实际比对的枚举组过少（" + compared + "），门禁可能失效");
        assertTrue(problems.isEmpty(),
                "以下取值集合在前端清单与后端枚举之间不一致 —— 同一组取值两处手工维护，"
                        + "任一处漏改都会静默漂移（后端新增取值时前端不承认、或前端多出永远匹配不到的取值）。"
                        + "处置：对齐两侧，或让前端从单一来源派生：\n" + String.join("\n", problems));
    }

    private Set<String> backendEnumValues(String enumName) throws Exception {
        try (Stream<Path> paths = Files.walk(repoFile("server/src/main/java"))) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(enumName + ".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                int start = source.indexOf("public enum " + enumName);
                if (start < 0) continue;
                int open = source.indexOf('{', start);
                int close = source.indexOf('}', open);
                if (open < 0 || close < 0) continue;
                String body = source.substring(open + 1, close)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                // 枚举常量可能写在同一行（DRAFT, APPLIED, ...）或逐行；末位常量后无分隔符。
                // 故按 , 和 ; 切段后取每段中**最后一个**大写标识符（同时兼容多行写法）。
                Set<String> values = new LinkedHashSet<>();
                for (String part : body.split("[;,]")) {
                    Matcher matcher = Pattern.compile("([A-Z][A-Z0-9_]+)").matcher(part);
                    String last = null;
                    while (matcher.find()) {
                        last = matcher.group(1);
                    }
                    if (last != null) values.add(last);
                }
                if (!values.isEmpty()) return values;
            }
        }
        return Set.of();
    }

    /** {@code export type X = 'A' | 'B'} （允许跨行，直到语句结束）。 */
    private Set<String> frontendUnionType(String file, String symbol) throws Exception {
        String source = Files.readString(repoFile(file), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "export\\s+type\\s+" + Pattern.quote(symbol) + "\\s*=([^;\\n]*)").matcher(source);
        if (!matcher.find()) return Set.of();
        return literals(matcher.group(1));
    }

    /** {@code export const X = ['A','B'] as const}。 */
    private Set<String> frontendArrayLiteral(String file, String symbol) throws Exception {
        String source = Files.readString(repoFile(file), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "export\\s+const\\s+" + Pattern.quote(symbol) + "\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL)
                .matcher(source);
        if (!matcher.find()) return Set.of();
        return literals(matcher.group(1));
    }

    private Set<String> literals(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = STRING_LITERAL.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String difference(Set<String> left, Set<String> right) {
        Set<String> diff = new TreeSet<>(left);
        diff.removeAll(right);
        return diff.isEmpty() ? "（无）" : String.join(", ", diff);
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
