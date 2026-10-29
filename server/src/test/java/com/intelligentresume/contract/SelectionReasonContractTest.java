package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code selection_reason} 取值域封闭性门禁（D5，ADR-013）。
 *
 * <p>背景：该字段此前是 {@code sel.get("selectedReason").toString()} 直接透传 ——
 * 把溯源字段的取值域交给了上游字符串；实测全仓只有两个写入点，不存在第三个来源。
 * 未知值静默落库等于伪造溯源（查"这条引用从哪来"时给出无法解释的值）。
 *
 * <p>三条断言分别钉：①枚举取值集合固定两值；②写入路径不得再出现裸字符串透传；
 * ③该字段没有第二个写入点（否则归一化会被绕过）。
 */
class SelectionReasonContractTest {

    private static final String ENUM =
            "server/src/main/java/com/intelligentresume/resume/domain/SelectionReason.java";
    private static final String SERVICE =
            "server/src/main/java/com/intelligentresume/ai/confirmation/service/DraftCommitService.java";
    private static final String PROVENANCE =
            "server/src/main/java/com/intelligentresume/ai/confirmation/service/SelectionProvenance.java";
    private static final String MAIN_JAVA = "server/src/main/java";
    private static final String DOC = "docs/04-数据库设计说明书.md";

    @Test
    @DisplayName("取值域必须恰好是 AI_REFERENCED / USER_EDITED 两值（新增值需先改 ADR-013）")
    void valueSetIsExactlyTwoValues() throws Exception {
        List<String> constants = enumConstants(read(ENUM));
        // 解析失败不得静默通过：一个解析不到常量的门禁等于没跑
        assertFalse(constants.isEmpty(), ENUM + " 未解析到任何枚举常量 —— 门禁解析失效（格式变了？），不得视为通过");
        assertEquals(2, constants.size(), ENUM + " 的取值域不是两值，实际：" + constants
                + "。取值域是封闭两值（docs/04 表节 + ADR-013）：新增值会让历史引用行的口径失去可比性，"
                + "必须同步改 docs/04、ADR-013 与前端联合类型，不能只加一个枚举值");
        assertEquals(List.of("AI_REFERENCED", "USER_EDITED"), constants,
                ENUM + " 的常量集合与决策不符，实际：" + constants);
    }

    @Test
    @DisplayName("写入路径必须经 SelectionReason 归一化，不得透传裸字符串")
    void writePathGoesThroughNormalizer() throws Exception {
        // 先抹白注释：源码里要写"反例：saveReference(..., selectedReason)"来说明为什么禁止，
        // 不过滤会让说明性注释触发本断言（与 OpenDecisionRegister/BackupRetention 同一纪律）
        String source = stripComments(read(SERVICE));
        // 归一化实现在 SelectionProvenance（2026-10-29 因行数棘轮抽出）：两处都必须成立，
        // 否则调用点被搬回主流程时本门禁会静默失效
        assertTrue(source.contains("SelectionProvenance.validateAndNormalize("),
                SERVICE + " 未走 SelectionProvenance.validateAndNormalize —— 取值域又交回给上游字符串了");
        assertTrue(read(PROVENANCE).contains("SelectionReason.fromClient("),
                PROVENANCE + " 未调用 SelectionReason.fromClient —— 归一化环节被绕过");
        // 裸透传的具体形态：把 selectedReason 直接塞进 saveReference 的参数位
        Pattern rawPassThrough = Pattern.compile("saveReference\\([^;]*selectedReason", Pattern.DOTALL);
        assertFalse(rawPassThrough.matcher(source).find(),
                SERVICE + " 仍把 selectedReason 裸透传进 saveReference —— 未知值会静默落库（伪造溯源）");
        assertFalse(source.contains("saveReference(version.getId(), material.getId(), edited.getKey(), editedSnapshot, \"USER_EDITED\")"),
                SERVICE + " 仍用字面量 \"USER_EDITED\" 写引用 —— 必须走 SelectionReason.USER_EDITED.dbValue()，"
                        + "否则改枚举时这里会漂移");
    }

    @Test
    @DisplayName("selection_reason 只有一个写入点（第二个写入点会绕过归一化）")
    void singleWritePoint() throws Exception {
        List<String> callers = new ArrayList<>();
        Path root = repoFile(MAIN_JAVA);
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("setSelectionReason(")) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                // 实体/值对象上的 setter **定义**不是写入点；只有调用方才算
                if (relative.endsWith("ResumeMaterialReference.java")) {
                    continue;
                }
                callers.add(relative);
            }
        }
        assertEquals(List.of("com/intelligentresume/ai/confirmation/service/DraftCommitService.java"), callers,
                MAIN_JAVA + " 下**调用** setSelectionReason 的文件不止一处：" + callers
                        + "。归一化只在 saveReference 一处生效，新增写入点必须同步走 SelectionReason 并更新本断言"
                        + "（判断依据：setter 定义本身不算写入点，只统计调用方）");
    }

    @Test
    @DisplayName("docs/04 必须写明取值域与『不做 DB CHECK 约束』的理由")
    void documentationStatesValueDomain() throws Exception {
        String doc = read(DOC);
        assertTrue(doc.contains("selection_reason"), DOC + " 未提及 selection_reason 字段");
        assertTrue(doc.contains("AI_REFERENCED") && doc.contains("USER_EDITED"),
                DOC + " 未写明 selection_reason 的两值取值域（AI_REFERENCED / USER_EDITED）—— "
                        + "取值域只存在于代码里时，下游按该字段分档/统计的人无从判断合法取值");
    }

    // ---- helpers ----

    /**
     * 解析枚举常量区（第一个 {@code {} 之后到首个 {@code ;} 之间），按逗号切分。
     *
     * <p>不能用「行首大写词」这类正则：常量写在同一行（{@code USER_EDITED, GHOST;}）
     * 时会被漏掉 —— 审计实测该写法曾让本门禁**假绿**（2026-10-29）。
     */
    private List<String> enumConstants(String source) {
        String code = stripComments(source);
        int bodyStart = code.indexOf('{');
        assertTrue(bodyStart > 0, ENUM + " 结构异常：找不到类体起始花括号");
        int terminal = code.indexOf(';', bodyStart);
        assertTrue(terminal > bodyStart, ENUM + " 结构异常：枚举常量区没有结束分号");
        List<String> names = new ArrayList<>();
        for (String raw : code.substring(bodyStart + 1, terminal).split(",")) {
            String name = raw.trim();
            int paren = name.indexOf('(');
            if (paren > 0) {
                name = name.substring(0, paren).trim();
            }
            if (name.matches("[A-Z][A-Z0-9_]*")) {
                names.add(name);
            }
        }
        return names;
    }

    /** 抹白行注释与块注释（保留换行，避免行号/相对位置错乱）。 */
    private String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private String read(String relative) throws Exception {
        return SourceText.read(repoFile(relative));
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到路径: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
