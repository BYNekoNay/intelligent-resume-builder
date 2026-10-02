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
 * 前端类型 ← → 后端 DTO 字段门禁（静态）（第五十七批）。
 *
 * <p>背景：前端 TS 接口声明的字段若在后端 DTO 里**不存在**，运行期取到的是 {@code undefined}——
 * 界面会渲染空白或 {@code Invalid Date}，而编译期与既有的 e2e 都可能不报错（新增字段往往还没接 UI）。
 * 第五十七批对账同名与显式映射的全部类型对，实测出 1 处：
 * {@code ResumeSummary.createdAt}（前端声明、后端 {@code ResumeSummary} 无该字段、UI 亦未使用）。
 *
 * <p>断言（单向）：前端类型的字段集合必须是后端 DTO 字段集合的**子集**。
 * 反向不约束：后端返回而前端未声明是合理的（前端按需声明，如 {@code InterviewStateResponse} 只声明 17/37）。
 *
 * <p>覆盖方式：
 * <ol>
 *   <li><b>同名类型</b>自动配对；</li>
 *   <li>名字不同但语义相同的由 {@link #NAME_MAPPING} 显式登记（不登记则只按同名匹配，不会误报）；</li>
 *   <li>前端 {@code extends} 的基接口在同文件内**递归展开**（否则只会看到子接口自己的字段）。</li>
 * </ol>
 */
class DtoFieldContractTest {

    /** 前端类型名 → 后端 DTO 名（语义相同但命名不同）。 */
    private static final Map<String, String> NAME_MAPPING = Map.of(
            "AiTask", "AiTaskStatusResponse",
            "ApplicationRecord", "ApplicationResponse",
            "CurrentUser", "CurrentUserResponse",
            "ExportTask", "ExportTaskStatusResponse",
            "InterviewSessionSummary", "InterviewSessionSummaryResponse",
            "MatchExplanation", "Explanation",
            "ResumeVersion", "ResumeVersionDetail",
            "SystemHealth", "SystemHealthResponse");

    private static final Pattern BACKEND_RECORD =
            Pattern.compile("public\\s+record\\s+(\\w+)\\s*\\((.*?)\\)\\s*\\{", Pattern.DOTALL);

    private static final Pattern BACKEND_FIELD =
            Pattern.compile("private\\s+(?:final\\s+)?[\\w<>,\\[\\].]+\\s+(\\w+)\\s*[;=]");

    private static final Pattern FRONT_INTERFACE = Pattern.compile(
            "export\\s+interface\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+?))?\\s*\\{(.*?)\\n\\}", Pattern.DOTALL);

    private static final Pattern FRONT_FIELD = Pattern.compile("(?m)^\\s*(\\w+)\\s*\\??\\s*:");

    /** 前端类型定义（字段 + 基接口名）。 */
    private record FrontType(Set<String> fields, List<String> bases) { }

    @Test
    @DisplayName("前端类型的字段在后端 DTO 中都存在（否则运行期 undefined）")
    void frontendFieldsExistInBackendDto() throws Exception {
        Map<String, Set<String>> backend = backendDtoFields();
        Map<String, FrontType> frontend = frontendTypes();

        assertTrue(backend.size() >= 60,
                "解析到的后端 DTO 过少（" + backend.size() + "），门禁可能未生效");
        assertTrue(frontend.size() >= 40,
                "解析到的前端类型过少（" + frontend.size() + "），门禁可能失效");

        List<String> violations = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, FrontType> entry : new TreeMap<>(frontend).entrySet()) {
            String frontName = entry.getKey();
            String backendName = NAME_MAPPING.getOrDefault(frontName, frontName);
            Set<String> backendFields = backend.get(backendName);
            if (backendFields == null) continue;   // 后端无对应 DTO（如纯前端视图模型）→ 不约束
            compared++;
            Set<String> frontFields = resolveFrontFields(frontName, frontend, new LinkedHashSet<>());
            List<String> extra = new ArrayList<>();
            for (String field : frontFields) {
                if (!backendFields.contains(field)) extra.add(field);
            }
            if (!extra.isEmpty()) {
                violations.add("  " + frontName + " ←→ " + backendName + "：前端多出 ["
                        + String.join(", ", extra) + "]；后端实际解析到=" + backendFields);
            }
        }

        assertTrue(compared >= 20,
                "实际比对的前后端类型对过少（" + compared + "），门禁可能失效（命名或解析变化？）");
        assertTrue(violations.isEmpty(),
                "以下前端类型声明了后端 DTO **不存在**的字段 —— 运行期取到 undefined（界面空白或 Invalid Date），"
                        + "而 TS 编译与 e2e 都可能不报错。处置：修正前端字段名，或补后端 DTO 字段"
                        + "（若两端命名本就不同，登记进 NAME_MAPPING）：\n" + String.join("\n", violations));
    }

    private Set<String> resolveFrontFields(String name, Map<String, FrontType> types, Set<String> seen) {
        FrontType type = types.get(name);
        if (type == null || !seen.add(name)) return new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>(type.fields());
        for (String base : type.bases()) {
            fields.addAll(resolveFrontFields(base, types, seen));
        }
        return fields;
    }

    private Map<String, Set<String>> backendDtoFields() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(repoFile("server/src/main/java"))) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/dto/")).toList()) {
                String source = SourceText.read(file);
                Matcher recordMatcher = BACKEND_RECORD.matcher(source);
                Set<String> fields = new LinkedHashSet<>();
                String name = null;
                if (recordMatcher.find()) {
                    name = recordMatcher.group(1);
                    fields.addAll(splitRecordComponents(recordMatcher.group(2)));
                } else {
                    Matcher classMatcher = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(source);
                    if (!classMatcher.find()) continue;
                    name = classMatcher.group(1);
                }
                Matcher fieldMatcher = BACKEND_FIELD.matcher(source);
                while (fieldMatcher.find()) {
                    fields.add(fieldMatcher.group(1));
                }
                result.put(name, fields);
            }
        }
        assertTrue(!result.isEmpty(), "未解析到任何后端 DTO —— 目录结构可能已变化");
        int totalFields = result.values().stream().mapToInt(Set::size).sum();
        assertTrue(totalFields >= 300,
                "后端 DTO 字段总数过少（" + totalFields + "），DTO 数 " + result.size()
                        + "；样例 AiTaskStatusResponse=" + result.get("AiTaskStatusResponse")
                        + "；样例 ApplicationSummary=" + result.get("ApplicationSummary"));
        return result;
    }

    /** 按顶层逗号切分 record 组件（忽略泛型里的逗号）。 */
    private List<String> splitRecordComponents(String inside) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char ch : inside.toCharArray()) {
            if (ch == '<' || ch == '(' || ch == '[') depth++;
            else if (ch == '>' || ch == ')' || ch == ']') depth--;
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.toString().isBlank()) parts.add(current.toString());

        // 每个片段形如 "Long id" / "Map<String, Object> resultJson" → 末位 token 即字段名
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String[] tokens = part.trim().split("\\s+");
            if (tokens.length >= 2) names.add(tokens[tokens.length - 1]);
        }
        return names;
    }

    private Map<String, FrontType> frontendTypes() throws Exception {
        Map<String, FrontType> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(repoFile("web/src/api"))) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".ts")).toList()) {
                String source = SourceText.read(file);
                Matcher matcher = FRONT_INTERFACE.matcher(source);
                while (matcher.find()) {
                    Set<String> fields = new LinkedHashSet<>();
                    Matcher fieldMatcher = FRONT_FIELD.matcher(matcher.group(3));
                    while (fieldMatcher.find()) {
                        fields.add(fieldMatcher.group(1));
                    }
                    List<String> bases = new ArrayList<>();
                    if (matcher.group(2) != null) {
                        for (String base : matcher.group(2).split(",")) {
                            if (!base.isBlank()) bases.add(base.trim());
                        }
                    }
                    result.put(matcher.group(1), new FrontType(fields, bases));
                }
            }
        }
        return result;
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
