package com.intelligentresume.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 契约门禁读取源码/配置时的**文本预处理**：把注释**原地抹成等长空白**后再交给文本判据。
 *
 * <p>刻意「抹白」而不是「删除」：不少判据是**距离窗口**式的（如「{@code @Transactional} 之后 300
 * 字符内不得出现 X」）。删掉注释会缩短文本、让窗口变紧，从而制造**假红** —— 第六十六批实测踩到
 * （`ExportStreamingContractTest` 因此由绿转红）。抹白保留长度与换行，于是「注释内容不再命中」
 * 与「距离语义不变」两者兼得。
 *
 * <p>为什么必须有它（第六十六批取证）：门禁普遍用「在文件全文里找某个 token」作判据，
 * 而**注释里的同名文本会让判据假绿**。已实测确认：把 `JdKeywordParser` 里真正消费
 * `app.job.jd-text.min-length` 的 `@Value` 去掉后，`ConfigConsumerContractTest`
 * **仍然通过** —— 因为该类的 javadoc 里正好写着这个键名。而这条门禁守护的恰恰是
 * 本项目吃过两次亏的「配置声明必须有消费点」。
 *
 * <p>规则（按扩展名决定注释风格，未知类型**不剥**更安全）：
 * <ul>
 *   <li>`.java/.js/.mjs/.cjs/.ts/.tsx/.vue`：C 风格 `//` 与 `/* *&#47;`；</li>
 *   <li>`.yml/.yaml/.conf/.service/.properties/.sh/.example/.template`：`#`；</li>
 *   <li>`.sql`：`--`；</li>
 *   <li>`.md/.json` 及未知类型：原样返回（文档与 JSON 没有注释语义）。</li>
 * </ul>
 *
 * <p><b>刻意保留字符串字面量</b>（只剥注释）：不少"消费点"本身就是字符串里的占位符
 * （如 `@Scheduled(fixedDelayString = "${app.x.y:1}")`），剥掉会制造假红。
 * 因此 C 风格实现是**词法级**的 —— 会跟踪字符串/字符字面量与 Java 文本块（`"""`），
 * 不会把 `"http://host"` 里的 `//` 当成注释（朴素 `indexOf("//")` 就会犯这个错）。
 *
 * <p><b>残留</b>（已登记）：字符串字面量里的 token 仍会被算作命中。实践中这比注释罕见得多，
 * 且多数命中本身就是合法消费点；如后续出现真实案例再评估。
 */
final class SourceText {

    private SourceText() {
    }

    /** 读文件并按扩展名剥注释。 */
    static String read(Path path) throws IOException {
        return stripComments(Files.readString(path, StandardCharsets.UTF_8), path.getFileName().toString());
    }

    /** 按文件名（扩展名）选择注释风格后剥注释。 */
    static String stripComments(String source, String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".json")) {
            return source;
        }
        if (name.endsWith(".sql")) {
            return stripHashStyle(source, "--");
        }
        if (HASH_STYLE.matcher("." + name).matches()) {
            return stripHashStyle(source, "#");
        }
        if (C_STYLE.matcher("." + name).matches()) {
            return stripCStyle(source);
        }
        return source;
    }

    private static final java.util.regex.Pattern C_STYLE = java.util.regex.Pattern.compile(
            ".*\\.(java|js|mjs|cjs|ts|tsx|vue)");
    private static final java.util.regex.Pattern HASH_STYLE = java.util.regex.Pattern.compile(
            ".*\\.(yml|yaml|conf|service|properties|sh|example|template)");

    /** C 风格：词法级扫描，跟踪 `"…"`、`'…'`、Java 文本块 `"""…"""` 与转义。 */
    private static String stripCStyle(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                if (source.startsWith("\"\"\"", i)) {
                    int end = source.indexOf("\"\"\"", i + 3);
                    int stop = end < 0 ? n : end + 3;
                    out.append(source, i, stop);
                    i = stop;
                } else {
                    int stop = endOfQuoted(source, i + 1, '"');
                    out.append(source, i, stop);
                    i = stop;
                }
            } else if (c == '\'') {
                int stop = endOfQuoted(source, i + 1, '\'');
                out.append(source, i, stop);
                i = stop;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 从 {@code from} 起找到收尾引号（含）后的下标；遇未转义的换行即中止（未闭合字面量）。 */
    private static int endOfQuoted(String source, int from, char quote) {
        int n = source.length();
        int j = from;
        while (j < n) {
            char d = source.charAt(j);
            if (d == '\\') {
                j += 2;
                continue;
            }
            if (d == quote || d == '\n') {
                j++;
                break;
            }
            j++;
        }
        return Math.min(j, n);
    }

    /** `#` / `--` 风格：逐行抹白，且**不碰引号内**（`.env` 的值、SQL 里的字符串可能含标记）。 */
    private static String stripHashStyle(String source, String marker) {
        StringBuilder out = new StringBuilder(source.length());
        String[] lines = source.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int cut = indexOfOutsideQuotes(line, marker);
            if (cut < 0) {
                out.append(line);
            } else {
                out.append(line, 0, cut);
                out.append(" ".repeat(line.length() - cut));
            }
            if (li < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static int indexOfOutsideQuotes(String line, String marker) {
        char quote = 0;
        for (int i = 0; i + marker.length() <= line.length(); i++) {
            char c = line.charAt(i);
            if (quote == 0) {
                if (c == '"' || c == '\'') {
                    quote = c;
                } else if (line.startsWith(marker, i)) {
                    return i;
                }
            } else if (c == quote) {
                quote = 0;
            }
        }
        return -1;
    }
}
