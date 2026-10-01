package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境变量样例门禁（第五十四批）：{@code server/.env.example} 是配置的**第三个来源**
 * （其余两个是代码 {@code @Value} 兜底与 {@code application.yml} 默认值），此前**不在任何门禁覆盖内**。
 *
 * <p>它有两个失效形态，都属于「照抄样例即踩坑」：
 * <ol>
 *   <li><b>键在样例里、却无 yml 占位符</b> —— 运维照抄后该键被静默忽略（
 *       {@code PROJECT_CONTEXT.md} 记录的配置铁律）；第五十四批实测出
 *       {@code AI_MOCK_FAIL_RATE} / {@code AI_MOCK_LATENCY_MS} 两个死键（Mock 模型早已不是正常功能路径）。</li>
 *   <li><b>值与该键的 yml 默认值不一致</b> —— 最危险的是「样例比 yml 更保守/更激进」而看不出：
 *       实测 {@code AI_WORKER_LEASE_S=60} 而 yml 是 {@code 660}，照抄即把租约压到链总预算（600s）之下，
 *       长任务会被接管重跑、重复调用 provider（正是第一批 #60 修掉的问题）。</li>
 * </ol>
 *
 * <p>列表型默认值（yml 默认值含逗号，如 {@code CORS_ALLOWED_ORIGINS}）跳过值比对：样例给出最小可用
 * 子集是合理的，键本身仍必须在 yml 有占位符。
 */
class EnvExampleContractTest {

    private static final Pattern ENV_LINE = Pattern.compile("^([A-Z_][A-Z0-9_]*)=(.*)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*):([^}]*)\\}");

    /** 解析 {@code KEY=VALUE}（忽略注释与空行）。 */
    private List<Map.Entry<String, String>> envEntries() throws Exception {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (String line : Files.readString(repoFile("server/.env.example"), StandardCharsets.UTF_8).split("\n")) {
            Matcher matcher = ENV_LINE.matcher(line.trim());
            if (matcher.matches()) {
                entries.add(Map.entry(matcher.group(1), matcher.group(2).trim()));
            }
        }
        return entries;
    }

    /** application.yml 中 {@code ${KEY:default}} → 默认值（同一键多处出现时保留全部）。 */
    private Map<String, List<String>> yamlPlaceholders() throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String yaml = Files.readString(repoFile("server/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        Matcher matcher = PLACEHOLDER.matcher(yaml);
        while (matcher.find()) {
            result.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(matcher.group(2));
        }
        return result;
    }

    @Test
    @DisplayName(".env.example 的每个键都在 application.yml 有 ${} 占位符（否则照抄即被静默忽略）")
    void everyKeyHasYamlPlaceholder() throws Exception {
        List<Map.Entry<String, String>> entries = envEntries();
        Map<String, List<String>> placeholders = yamlPlaceholders();

        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            if (!placeholders.containsKey(entry.getKey())) {
                orphans.add("  " + entry.getKey());
            }
        }

        assertTrue(entries.size() >= 25,
                "解析到的 .env.example 键过少（" + entries.size() + "），门禁可能未生效");
        assertTrue(orphans.isEmpty(),
                "以下键出现在 server/.env.example，但 application.yml 没有对应的 ${} 占位符 —— "
                        + "照抄样例后这些键会被**静默忽略**（无任何提示）。处置：补占位符并实现消费点，"
                        + "或从样例移除（本项目铁律：只写进 .env 而无占位符消费的项会被静默忽略）：\n"
                        + String.join("\n", orphans));
    }

    @Test
    @DisplayName(".env.example 的标量值与 application.yml 同名默认值一致")
    void scalarValuesMatchYamlDefaults() throws Exception {
        List<Map.Entry<String, String>> entries = envEntries();
        Map<String, List<String>> placeholders = yamlPlaceholders();

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, String> entry : entries) {
            List<String> defaults = placeholders.get(entry.getKey());
            if (defaults == null || defaults.isEmpty()) continue;
            String yamlDefault = defaults.get(0);
            // 列表型默认值（含逗号）跳过值比对：样例给出最小可用子集是合理的
            if (yamlDefault.contains(",")) continue;
            compared++;
            if (!entry.getValue().equals(yamlDefault)) {
                mismatches.add("  " + entry.getKey() + " → 样例 [" + entry.getValue() + "] vs yml 默认 [" + yamlDefault + "]");
            }
        }

        assertTrue(compared >= 20,
                "可比对的 .env.example 标量值过少（" + compared + "），门禁可能失效");
        assertTrue(mismatches.isEmpty(),
                "以下环境变量样例值与 application.yml 默认值不一致 —— 照抄样例会静默采用另一个值：\n"
                        + String.join("\n", mismatches));
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
