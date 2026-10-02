package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务错误码三处一致性门禁（静态）（第五十九批）。
 *
 * <p>同一个错误码在**三处**分别维护：后端 {@code ErrorCode} 枚举（唯一来源）、
 * {@code docs/05} §1.3 的通用错误码表（集成方/评审据此实现）、前端
 * {@code web/src/utils/errorCodes.ts} 的码→文案键映射（用户可见提示）。任一处漏改都静默漂移：
 * 后端新增码而文档没写 → 集成方不知道要处理该分支；前端没登记 → 用户看到泛化文案。
 *
 * <p>第五十九批实测出 1 处：文档表只有 10 个码，**漏 {@code 40302}（AI 数据处理未授权/已撤回）**
 * —— 这正是 AI 能力的前置错误码，照文档实现的调用方会漏掉该分支。
 */
class ErrorCodeContractTest {

    private static final Pattern ENUM_ENTRY =
            Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\((\\d{5}),", Pattern.MULTILINE);

    /** docs/05 §1.3 表格行：{@code | 40001 | 说明 |}。 */
    private static final Pattern DOC_TABLE_ROW = Pattern.compile("(?m)^\\|\\s*(\\d{5})\\s*\\|");

    /** 前端映射条目：{@code 40001: 'errors.validation',}。 */
    private static final Pattern FRONT_MAP_ENTRY = Pattern.compile("(?m)^\\s*(\\d{5})\\s*:");

    @Test
    @DisplayName("错误码在后端枚举 / docs/05 §1.3 / 前端映射三处一致")
    void errorCodesStayInSync() throws Exception {
        Set<String> backend = extract(repoFile("server/src/main/java/com/intelligentresume/common/error/ErrorCode.java"),
                ENUM_ENTRY, 2);
        Set<String> doc = extractSection(repoFile("docs/05-接口设计说明书.md"));
        Set<String> frontend = extract(repoFile("web/src/utils/errorCodes.ts"), FRONT_MAP_ENTRY, 1);

        assertTrue(backend.size() >= 8, "后端解析到的错误码过少（" + backend.size() + "），门禁可能未生效");
        assertTrue(!doc.isEmpty(), "未从 docs/05 §1.3 解析到任何错误码 —— 章节标题或表格式可能变化");
        assertTrue(frontend.size() >= 8, "前端解析到的错误码过少（" + frontend.size() + "），门禁可能失效");

        StringBuilder problems = new StringBuilder();
        appendDiff(problems, "后端枚举", backend, "docs/05 §1.3 表", doc);
        appendDiff(problems, "后端枚举", backend, "前端 errorCodes.ts", frontend);

        assertTrue(problems.isEmpty(),
                "以下错误码在三处（后端枚举 / docs/05 §1.3 / 前端映射）之间不一致 —— 集成方与前端分别据此实现，"
                        + "任一处漏改都会静默漂移（文档漏写→调用方不知要处理；前端漏登记→用户看到泛化文案）：\n"
                        + problems);
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

    private Set<String> extract(Path file, Pattern pattern, int group) throws Exception {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(SourceText.read(file));
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return values;
    }

    /** 只取 docs/05 §1.3 小节内的码（避免误收其它章节的数字）。 */
    private Set<String> extractSection(Path docFile) throws Exception {
        String doc = SourceText.read(docFile);
        int start = doc.indexOf("### 1.3");
        assertTrue(start >= 0, "docs/05 找不到 §1.3 小节（标题可能已改名）");
        int end = doc.length();
        for (String marker : List.of("\n## ", "\n### ")) {
            int idx = doc.indexOf(marker, start + 1);
            if (idx > start && idx < end) {
                end = idx;
            }
        }
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = DOC_TABLE_ROW.matcher(doc.substring(start, end));
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
