package com.intelligentresume.contract;

import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.service.ApplicationService;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 投递状态迁移矩阵「三份手工副本」的解析器，供 {@link ApplicationStatusMatrixContractTest} 对账使用。
 *
 * <p>为什么单独成类：同一份迁移矩阵在后端 {@code ApplicationService.TRANSITIONS}、前端
 * {@code ApplicationsView.vue} 的 {@code transitions}、{@code docs/05} 的状态机表格里各维护一份，
 * 三份的解析方式、锚点与失效模式都不同（反射 / 字面量块 / Markdown 表格），全塞进门禁类会让它越过
 * 项目 300 行硬门禁。拆出来后门禁类只留「比对与报错」，将来加第四来源也不必再改结构。
 *
 * <p><b>统一失效语义：解析不到一律 fail，绝不返回空集合让上层「空 vs 空」假通过。</b>
 * 每条 {@code fail()} 文案都写明「不允许跳过」或「不允许猜一个」，便于下一个人自查。
 */
final class StatusMatrixSources {

    private StatusMatrixSources() {
    }

    /** 前端镜像表所在文件（仓库相对路径）。 */
    private static final String FRONTEND_FILE = "web/src/views/ApplicationsView.vue";

    /**
     * 前端矩阵字面量的定位锚点 —— **整行匹配**（与 {@link #DOCS_TABLE_HEADER} 同构），绝不用全文 {@code indexOf}。
     *
     * <p>为什么：裸子串 {@code "const transitions"} 会命中 {@code const transitionsByStatus} 这类改名后的变体，
     * 也会命中注释里抄写的同款文本 —— 解析器随后在「错的那一行」之后再找第一个 {@code {}，得到半截块，
     * 报出来的失败位置看起来完全正常，误导排查。整行匹配 + 显式 {@code :} 分隔 + 行尾 {@code {} 三重约束
     * 把这两种误命中一起挡掉。
     *
     * <p>只用 {@code [ \t]} 而不用 {@code \s}：后者含换行，会让"整行"约束失效。
     */
    private static final Pattern FRONTEND_ANCHOR = Pattern.compile(
            "(?m)^[ \\t]*const[ \\t]+transitions[ \\t]*(?::[^=\\n]*)?=[ \\t]*\\{[ \\t\\r]*$");

    /** 前端一行一个状态：{@code NO_RESPONSE: ['NO_RESPONSE', 'APPLIED', ...],}。 */
    private static final Pattern FRONTEND_ROW = Pattern.compile(
            "(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*:\\s*\\[([^\\]]*)\\]");

    private static final Pattern QUOTED_STATUS = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    /** 文档镜像表所在文件（仓库相对路径）。 */
    static final String DOCS_FILE = "docs/05-接口设计说明书.md";

    /**
     * 表头行的定位锚点 —— **整行匹配**，绝不用全文 {@code indexOf}。
     *
     * <p>为什么：文档正文可以合法地引用表头文字（例如耦合声明里写「本表表头是 …」）。全文
     * {@code indexOf} 会命中**散文里那一处**而不是真表头，之后向下切行只能拿到半截文本，
     * 结果是报「找不到迁移表头」而表头明明在 —— 失败信息指向的位置看起来完全正常，误导排查。
     * 整行匹配要求该行除表头外再无其它字符，散文里嵌了这段文字的行必然不等，天然免疫。
     */
    private static final Pattern DOCS_TABLE_HEADER =
            Pattern.compile("^\\|\\s*当前状态\\s*\\|\\s*允许迁移到\\s*\\|$");

    /** 文档表格里状态名一律反引号包裹；目标之间用中文顿号分隔。 */
    private static final Pattern BACKTICKED_STATUS = Pattern.compile("`([A-Z][A-Z0-9_]*)`");

    /** 后端基准：反射读 {@code private static final TRANSITIONS}（不改可见性、不新增生产访问器）。 */
    @SuppressWarnings("unchecked")
    static Map<ApplicationStatus, Set<ApplicationStatus>> backend() {
        try {
            Field field = ApplicationService.class.getDeclaredField("TRANSITIONS");
            field.setAccessible(true);
            return (Map<ApplicationStatus, Set<ApplicationStatus>>) field.get(null);
        } catch (ReflectiveOperationException e) {
            fail("无法读取 ApplicationService.TRANSITIONS（字段被改名/移除？），"
                    + "门禁解析逻辑需同步更新: " + e);
            return Map.of();
        }
    }

    /** 前端 {@code const transitions: Record<...> = { STATUS: [...], ... }} 字面量块。 */
    static Map<ApplicationStatus, Set<ApplicationStatus>> frontend() throws Exception {
        String source = SourceText.read(repoFile(FRONTEND_FILE));

        // 逐处整行匹配：先判重复、再判缺失，且都在「取用」之前 —— 顺序理由同 docs()：
        // 一旦先解析就已经"猜了一个"，而猜对时 assertParsed 的 9 行断言会直接绿，重复再也看不见。
        Matcher anchor = FRONTEND_ANCHOR.matcher(source);
        int anchorCount = 0;
        int blockOpen = -1;
        while (anchor.find()) {
            anchorCount++;
            if (blockOpen < 0) {
                // 正则已保证该行最后一个非空白字符是 '{'，回退到它即可；不再 indexOf 去找"别人的 {"。
                blockOpen = source.lastIndexOf('{', anchor.end() - 1);
            }
        }
        if (anchorCount > 1) {
            fail(FRONTEND_FILE + " 中匹配到 " + anchorCount + " 处整行锚点 `const transitions … = {` —— "
                    + "锚点不唯一（矩阵块被复制，或出现了第二个同名局部常量）。"
                    + "请确认唯一定义后重跑；门禁不允许在多义时猜一个（不允许跳过）");
        }
        if (anchorCount == 0) {
            fail("在 " + FRONTEND_FILE + " 中找不到整行匹配 " + FRONTEND_ANCHOR.pattern()
                    + " 的矩阵锚点 —— 前端结构可能已变更（局部常量改名同样视为变更），"
                    + "请同步更新门禁解析逻辑（不允许跳过）");
        }
        String block = objectLiteralAfter(source, blockOpen);

        Map<ApplicationStatus, Set<ApplicationStatus>> matrix = new LinkedHashMap<>();
        Matcher row = FRONTEND_ROW.matcher(block);
        while (row.find()) {
            ApplicationStatus from = parseStatus(row.group(1).trim());
            Set<ApplicationStatus> targets = EnumSet.noneOf(ApplicationStatus.class);
            Matcher literal = QUOTED_STATUS.matcher(row.group(2));
            while (literal.find()) {
                targets.add(parseStatus(literal.group(1)));
            }
            // 重复 from 不在此拦截：块内重复键由 vue-tsc 的 TS1117 在 npm run build 阶段先红，
            // 「重复挤掉另一状态」则由 assertParsed 的 9 行断言兜底；本处的独特职责是锚点唯一性。
            matrix.put(from, targets);
        }
        if (matrix.isEmpty()) {
            fail("未能从 " + FRONTEND_FILE + " 的 `const transitions … = {` 块解析出任何迁移行 —— "
                    + "前端结构可能已变更，请同步更新门禁解析逻辑（不允许跳过）");
        }
        return matrix;
    }

    /**
     * 文档表格：每行形如 {@code | `NO_RESPONSE` | `NO_RESPONSE`、`APPLIED`、… |}。
     * 一律按「反引号内的状态名」提取，不按顿号/逗号切分 —— 顿号只用于分隔目标，
     * 而 `（终态）` 之类的中文注解也在同一格内，按分隔符切会把注解当状态名。
     */
    static Map<ApplicationStatus, Set<ApplicationStatus>> docs() throws Exception {
        String source = SourceText.read(repoFile(DOCS_FILE));
        String[] lines = source.split("\\R");

        // 逐行找表头（整行匹配，见 DOCS_TABLE_HEADER 注释）：不唯一也必须红，不得默默取第一个。
        int headerLine = -1;
        int headerMatches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (DOCS_TABLE_HEADER.matcher(lines[i].trim()).matches()) {
                headerMatches++;
                if (headerLine < 0) {
                    headerLine = i;
                }
            }
        }
        if (headerMatches > 1) {
            fail(DOCS_FILE + " 中匹配到 " + headerMatches + " 个 `| 当前状态 | 允许迁移到 |` 表头行 —— "
                    + "表头锚点不唯一（可能表格被复制，或正文出现了独占一行的同款表头）。"
                    + "请确认唯一表头后重跑；门禁不允许在多义时猜一个（不允许跳过）");
        }
        if (headerLine < 0) {
            fail("在 " + DOCS_FILE + " 中找不到迁移表头行 `| 当前状态 | 允许迁移到 |` —— "
                    + "文档结构可能已变更，请同步更新门禁解析逻辑（不允许跳过）");
        }

        Map<ApplicationStatus, Set<ApplicationStatus>> matrix = new LinkedHashMap<>();
        // headerLine + 1 起：下一行是分隔行 `| --- | --- |`，其后为数据行；遇到非表格行即结束。
        for (int i = headerLine + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("|")) break;
            String[] cells = line.split("\\|", -1);
            if (cells.length < 3) break;
            List<String> fromCell = backticked(cells[1]);
            if (fromCell.isEmpty()) continue;   // 分隔行 `| --- | --- |`
            ApplicationStatus from = parseStatus(fromCell.get(0));
            Set<ApplicationStatus> targets = EnumSet.noneOf(ApplicationStatus.class);
            for (String name : backticked(cells[2])) {
                targets.add(parseStatus(name));
            }
            matrix.put(from, targets);
        }
        if (matrix.size() != ApplicationStatus.values().length) {
            fail(DOCS_FILE + " 的迁移表解析出 " + matrix.size() + " 行，应为 "
                    + ApplicationStatus.values().length + " 行 —— 表格结构可能已变更"
                    + "（行首反引号状态名 / 顿号分隔 / 每个状态各一行），"
                    + "请同步更新门禁解析逻辑（不允许跳过）");
        }
        return matrix;
    }

    /** 取以 {@code openBrace} 起始的 {@code {...}} 块的内容（brace 计数）。 */
    private static String objectLiteralAfter(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBrace + 1, i);
                }
            }
        }
        fail("`const transitions … = {` 的对象字面量未闭合 —— 前端结构可能已变更，请同步更新门禁解析逻辑");
        return "";
    }

    /** 提取文本中所有反引号包裹的状态名（按出现顺序）。 */
    private static List<String> backticked(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = BACKTICKED_STATUS.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static ApplicationStatus parseStatus(String raw) {
        try {
            return ApplicationStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            fail("前后端出现对方不认识的状态名 [" + raw + "] —— 需先补齐两侧枚举再更新本门禁");
            return null;
        }
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行（与 FrontendEnumContractTest 一致）。 */
    private static Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件/目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
