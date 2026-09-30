package com.intelligentresume.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 日志隐私静态门禁（ideation finding #68 防复发）。
 *
 * <p>`PROJECT_CONTEXT.md` 隐私边界：用户 ID 不得写入日志、指标标签、测试报告或 Git。
 * 本测试扫描主代码中的全部日志调用，禁止在日志模板或参数里出现
 * {@code userId}/{@code user_id}/{@code email}/{@code phone}；
 * {@code taskId}/{@code versionId}/{@code traceId} 等非用户身份标识保留用于排障。
 *
 * <p>实现说明：按 {@code log.xxx( ... );} 非贪婪截取调用片段（支持跨行），
 * 再对片段做禁词匹配。字符串字面量内出现 {@code );} 的极端写法可能漏检，属已知边界；
 * 若未来出现「非用户标识语义」的合理用词（例如日志文案里的 email 字样），
 * 请改写文案而非放宽本门禁。
 */
class LogPrivacyGateTest {

    private static final Pattern LOG_CALL = Pattern.compile(
            "log\\.(trace|debug|info|warn|error)\\s*\\(([\\s\\S]{0,600}?)\\)\\s*;");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(userId|user_id|email|phone)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void mainSourcesDoNotLogUserIdentifiers() throws IOException {
        // Maven surefire 的工作目录是 server/ 模块目录。
        Path sourceRoot = Path.of("src", "main", "java");
        if (!Files.isDirectory(sourceRoot)) {
            fail("源码根目录不存在（测试须在 server/ 模块目录下运行）: " + sourceRoot.toAbsolutePath());
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = LOG_CALL.matcher(source);
                while (matcher.find()) {
                    if (FORBIDDEN.matcher(matcher.group(2)).find()) {
                        long line = source.substring(0, matcher.start())
                                .chars().filter(ch -> ch == '\n').count() + 1;
                        violations.add(file + ":" + line);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "日志不得记录用户标识（userId/user_id/email/phone）；请移除对应参数或改写文案。违规: " + violations);
    }
}