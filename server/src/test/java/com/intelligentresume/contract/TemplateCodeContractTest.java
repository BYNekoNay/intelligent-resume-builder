package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简历模板代码四处一致性门禁（静态 · 跨运行时）（第六十批）。
 *
 * <p>同一组模板代码在**四处**分别维护，任一处漏改都会产生用户可见故障：
 * <ol>
 *   <li>后端 {@code ResumeTemplateCodes.SUPPORTED} —— 白名单，决定接受哪些 {@code templateCode}；</li>
 *   <li>{@code pdf-service/src/templates/classic.js} 的 {@code TEMPLATE_STYLES} —— **真正实现渲染**的样式；
 *       漏一处 → 后端放行的模板在 pdf-service 被判「不支持的简历模板」，**导出直接失败**；</li>
 *   <li>前端 {@code ResumeTemplateCode} 联合类型 —— TS 层面允许的取值；</li>
 *   <li>前端 {@code ResumeEditorView.vue} 的 {@code templateOptions} —— 用户**能选到**的模板。</li>
 * </ol>
 *
 * <p>第六十批对账结果：四处**完全一致（7 个）**。本门禁把该结论固化，防止将来加模板时漏改
 * （尤其 pdf-service —— 漏了它不会在编译期或既有测试中暴露，只会在用户点导出时才炸）。
 */
class TemplateCodeContractTest {

    private static final Pattern BACKEND_DEFAULT = Pattern.compile("DEFAULT\\s*=\\s*\"([a-z][a-z0-9]*)\"");

    private static final Pattern BACKEND_SET_OF = Pattern.compile("Set\\.of\\((.*?)\\)", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([a-z][a-z0-9]*)\"");

    /** pdf-service 的 {@code TEMPLATE_STYLES} 键：4 空格缩进 + 反引号模板串。 */
    private static final Pattern SERVICE_STYLE_KEY = Pattern.compile("(?m)^    ([a-z][a-z0-9]*): `");

    private static final Pattern FRONT_UNION =
            Pattern.compile("export\\s+type\\s+ResumeTemplateCode\\s*=([^;\\n]*)");

    private static final Pattern FRONT_OPTIONS_ARRAY =
            Pattern.compile("const\\s+templateOptions\\s*=\\s*\\[(.*?)\\]\\s*as\\s+const", Pattern.DOTALL);

    private static final Pattern FRONT_OPTION_CODE = Pattern.compile("code:\\s*'([a-z][a-z0-9]*)'");

    private static final Pattern TS_STRING = Pattern.compile("'([a-z][a-z0-9]*)'");

    @Test
    @DisplayName("简历模板代码在后端白名单 / pdf-service 样式 / 前端类型 / 前端选项 四处一致")
    void templateCodesStayInSync() throws Exception {
        Set<String> backend = backendSupported();
        Set<String> service = serviceStyleKeys();
        Set<String> frontType = frontUnion();
        Set<String> frontOptions = frontOptions();

        assertTrue(backend.size() >= 5, "后端解析到的模板过少（" + backend.size() + "），门禁可能未生效");
        assertTrue(service.size() >= 5, "pdf-service 解析到的模板过少（" + service.size() + "），门禁可能失效");
        assertTrue(frontType.size() >= 5, "前端联合类型解析到的模板过少（" + frontType.size() + "）");
        assertTrue(frontOptions.size() >= 5, "前端 templateOptions 解析到的模板过少（" + frontOptions.size() + "）");

        StringBuilder problems = new StringBuilder();
        appendDiff(problems, "后端 SUPPORTED", backend, "pdf-service 样式", service);
        appendDiff(problems, "后端 SUPPORTED", backend, "前端 ResumeTemplateCode", frontType);
        appendDiff(problems, "后端 SUPPORTED", backend, "前端 templateOptions", frontOptions);

        assertTrue(problems.isEmpty(),
                "以下模板代码没有在四处保持一致 —— 尤其 pdf-service 漏实现时**不会**在编译期或既有测试中暴露，"
                        + "只会在用户点导出时以「不支持的简历模板」失败：\n" + problems);
    }

    private void appendDiff(StringBuilder sb, String leftName, Set<String> left, String rightName, Set<String> right) {
        Set<String> onlyLeft = new TreeSet<>(left);
        onlyLeft.removeAll(right);
        Set<String> onlyRight = new TreeSet<>(right);
        onlyRight.removeAll(left);
        if (onlyLeft.isEmpty() && onlyRight.isEmpty()) return;
        sb.append("  ").append(leftName).append(" ←→ ").append(rightName)
                .append("：仅").append(leftName).append("有 ")
                .append(onlyLeft.isEmpty() ? "（无）" : String.join(", ", onlyLeft))
                .append("；仅").append(rightName).append("有 ")
                .append(onlyRight.isEmpty() ? "（无）" : String.join(", ", onlyRight))
                .append('\n');
    }

    /** {@code Set.of(DEFAULT, "modern", ...)} —— 其中的 {@code DEFAULT} 解析为其字面量。 */
    private Set<String> backendSupported() throws Exception {
        String source = Files.readString(
                repoFile("server/src/main/java/com/intelligentresume/resume/service/ResumeTemplateCodes.java"),
                StandardCharsets.UTF_8);
        Matcher defaultMatcher = BACKEND_DEFAULT.matcher(source);
        String defaultCode = defaultMatcher.find() ? defaultMatcher.group(1) : null;

        Matcher setMatcher = BACKEND_SET_OF.matcher(source);
        Set<String> values = new LinkedHashSet<>();
        if (setMatcher.find()) {
            for (String token : setMatcher.group(1).split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equals("DEFAULT") && defaultCode != null) {
                    values.add(defaultCode);
                } else if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    values.add(trimmed.substring(1, trimmed.length() - 1));
                }
            }
        }
        return values;
    }

    private Set<String> serviceStyleKeys() throws Exception {
        return collect("pdf-service/src/templates/classic.js", SERVICE_STYLE_KEY, 1);
    }

    private Set<String> frontUnion() throws Exception {
        String source = Files.readString(repoFile("web/src/api/export.ts"), StandardCharsets.UTF_8);
        Matcher matcher = FRONT_UNION.matcher(source);
        return matcher.find() ? literals(matcher.group(1), TS_STRING) : Set.of();
    }

    private Set<String> frontOptions() throws Exception {
        String source = Files.readString(repoFile("web/src/views/ResumeEditorView.vue"), StandardCharsets.UTF_8);
        Matcher matcher = FRONT_OPTIONS_ARRAY.matcher(source);
        return matcher.find() ? literals(matcher.group(1), FRONT_OPTION_CODE) : Set.of();
    }

    private Set<String> collect(String relative, Pattern pattern, int group) throws Exception {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(Files.readString(repoFile(relative), StandardCharsets.UTF_8));
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return values;
    }

    private Set<String> literals(String text, Pattern pattern) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
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
