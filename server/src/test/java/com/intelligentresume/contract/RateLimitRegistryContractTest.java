package com.intelligentresume.contract;

import com.intelligentresume.auth.ratelimit.RateLimitProperties;
import com.intelligentresume.auth.ratelimit.RateLimitRegistry;
import com.intelligentresume.auth.ratelimit.RateLimitRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流注册表三方对拍门禁：**代码规则 ↔ `application.yml` 默认值 ↔ `docs/05` 限流表**。
 *
 * <p>为什么需要它：限流的知识本来有三份副本，而且都出过错——
 * <ul>
 *   <li>代码里 {@code limitFor()} 的 if 链漏了 {@code /api/exports/pdf}、{@code /api/ats/check}、
 *       整个 {@code /api/ai/**}，这些端点长期没有频率闸；</li>
 *   <li>{@code application.yml} 的 {@code ${ENV:默认值}} 与 Java 字段默认值各写一遍，
 *       不一致时「yml 缺键」等于静默改阈值，谁也不知道线上真正是多少；</li>
 *   <li>文档只写「阈值见 `app.security.rate-limit.*`」，运维拿不到一张完整表，
 *       也看不出哪些端点其实根本没限。</li>
 * </ul>
 *
 * <p>门禁 fail-closed：表格行缺失、id 拼错、阈值不一致、分桶组写成路径、理由为空，任一成立即红。
 */
class RateLimitRegistryContractTest {

    /** 文档表格行：| 规则 | 方法 | 路径 | 默认阈值 | 配置键 | */
    private static final Pattern DOC_ROW = Pattern.compile(
            "^\\|\\s*([a-z][a-z0-9-]*)\\s*\\|([^|]*)\\|([^|]*)\\|\\s*([0-9]+)\\s*\\|\\s*`([a-z0-9-]+)`\\s*\\|");

    /** yml 里的 {@code ${ENV:默认}} 形态（默认值可能缺省）。 */
    private static final Pattern PROP_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+:([0-9]+)}$");

    @Test
    @DisplayName("docs/05 限流表与注册表双向一一对应（id 不多不少）")
    void docTableMatchesRegistryExactly() throws Exception {
        Map<String, DocRow> rows = readDocTable();
        Set<String> ruleIds = ids(RateLimitRegistry.build(new RateLimitProperties()));

        List<String> missingInDoc = new ArrayList<>();
        List<String> missingInCode = new ArrayList<>();
        for (String id : ruleIds) {
            if (!rows.containsKey(id)) {
                missingInDoc.add(id);
            }
        }
        for (String id : rows.keySet()) {
            if (!ruleIds.contains(id)) {
                missingInCode.add(id);
            }
        }

        assertTrue(missingInDoc.isEmpty(),
                "docs/05 限流表缺少这些规则（新增受限端点必须同时补文档，否则运维无从得知）：" + missingInDoc);
        assertTrue(missingInCode.isEmpty(),
                "docs/05 限流表有这些规则但代码里不存在（删规则时忘删文档，或 id 拼错）：" + missingInCode);
    }

    @Test
    @DisplayName("阈值三处一致：docs/05 表格 = RateLimitProperties 默认值 = application.yml 默认值")
    void thresholdsAgreeAcrossThreeCopies() throws Exception {
        Map<String, DocRow> rows = readDocTable();
        Map<String, Integer> ymlDefaults = readYmlDefaults();

        List<String> violations = new ArrayList<>();
        for (RateLimitRule rule : RateLimitRegistry.build(new RateLimitProperties())) {
            DocRow row = rows.get(rule.id());
            if (row == null) {
                continue;
            }
            Integer ymlValue = ymlDefaults.get(row.propertyKey());
            if (ymlValue == null) {
                violations.add(rule.id() + ": application.yml 缺少 app.security.rate-limit." + row.propertyKey());
                continue;
            }
            if (ymlValue != row.defaultLimit()) {
                violations.add(rule.id() + ": 文档写 " + row.defaultLimit() + "，yml 写 " + ymlValue);
            }
            if (rule.limitPerMinute() != row.defaultLimit()) {
                violations.add(rule.id() + ": 文档写 " + row.defaultLimit() + "，Java 默认 "
                        + rule.limitPerMinute());
            }
        }
        assertTrue(violations.isEmpty(),
                "同一阈值出现三种不一致的写法（任一不一致都会让「线上到底限多少」变成猜）：\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("分桶组必须是稳定组名：不含路径变量、不含具体 id、不含斜杠")
    void bucketGroupIsNeverAPath() {
        List<String> violations = new ArrayList<>();
        for (RateLimitRule rule : RateLimitRegistry.build(new RateLimitProperties())) {
            String group = rule.bucketGroup();
            if (group.contains("{") || group.contains("/")) {
                violations.add(rule.id() + " -> " + group);
            }
        }
        assertTrue(violations.isEmpty(),
                "分桶组里出现路径说明阈值会随 URL 基数放大（JD 解析曾因此把 15/min 放大成 15×JD 数）："
                        + violations);
    }

    @Test
    @DisplayName("每条规则都必须写明理由，且高成本端点不得落到 write-default 兜底")
    void rationalePresentAndCriticalEndpointsHaveOwnTier() {
        List<String> blank = new ArrayList<>();
        for (RateLimitRule rule : RateLimitRegistry.build(new RateLimitProperties())) {
            if (rule.rationale() == null || rule.rationale().isBlank()) {
                blank.add(rule.id());
            }
        }
        assertTrue(blank.isEmpty(), "以下规则没写理由（阈值就变成了拍脑袋）：" + blank);

        RateLimitRegistry registry = new RateLimitRegistry(new RateLimitProperties());
        // 具体 URL 形态（含占位符被替换后的真实值），验证「命中哪一档」
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("POST /api/jobs/42/parse", "jd-parse");
        expected.put("POST /api/exports/pdf", "pdf-render");
        expected.put("POST /api/ats/check", "ats-check");
        expected.put("POST /api/ai/tasks", "ai-generate");
        expected.put("POST /api/interviews/7/answer", "interview-turn");
        expected.put("POST /api/desktop/backups/3/restore", "desktop-mutation");
        expected.put("GET /api/system/health/detail", "health-detail");

        List<String> wrong = new ArrayList<>();
        expected.forEach((endpoint, expectedId) -> {
            String[] parts = endpoint.split(" ", 2);
            RateLimitRule rule = registry.resolve(parts[0], parts[1]);
            String actual = rule == null ? "<none>" : rule.id();
            if (!expectedId.equals(actual)) {
                wrong.add(endpoint + " 命中 " + actual + "，应为 " + expectedId);
            }
        });
        assertTrue(wrong.isEmpty(),
                "这些端点单次请求就会吃掉 CPU/Chromium/模型额度，不能被兜底档（240/min）盖住：\n  "
                        + String.join("\n  ", wrong));
    }

    private Set<String> ids(List<RateLimitRule> rules) {
        Set<String> ids = new LinkedHashSet<>();
        rules.forEach(rule -> ids.add(rule.id()));
        return ids;
    }

    /** 解析 docs/05 的限流表（表头行与分隔行不会命中 id 形态）。 */
    private Map<String, DocRow> readDocTable() throws Exception {
        Path doc = locateDoc();
        Map<String, DocRow> rows = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(doc)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = DOC_ROW.matcher(line.trim());
                if (matcher.matches()) {
                    rows.put(matcher.group(1), new DocRow(
                            matcher.group(3).trim(), Integer.parseInt(matcher.group(4)), matcher.group(5)));
                }
            }
        }
        assertFalse(rows.isEmpty(), "docs/05 里没有解析到限流表（表格格式被改动？门禁不能静默通过）");
        return rows;
    }

    /** 读 application.yml 的默认值：只认 `${ENV:默认}` 形态，缺省默认值也算违规。 */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> readYmlDefaults() throws Exception {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path yml = serverRoot.resolve("src/main/resources/application.yml");
        Map<String, Object> root = new Yaml().load(Files.newBufferedReader(yml));
        Map<String, Object> node = (Map<String, Object>) root;
        for (String key : new String[]{"app", "security", "rate-limit"}) {
            Object next = node.get(key);
            assertTrue(next instanceof Map, "application.yml 缺少 app.security.rate-limit 节点");
            node = (Map<String, Object>) next;
        }

        Map<String, Integer> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (!entry.getKey().endsWith("-per-minute")) {
                continue;
            }
            Matcher matcher = PROP_PLACEHOLDER.matcher(String.valueOf(entry.getValue()).trim());
            if (!matcher.matches()) {
                // 写死数字而不是 ${ENV:默认} 时，环境变量就再也覆盖不了它
                defaults.put(entry.getKey(), -1);
                continue;
            }
            defaults.put(entry.getKey(), Integer.parseInt(matcher.group(1)));
        }
        assertFalse(defaults.isEmpty(), "application.yml 里没解析到任何 *-per-minute 键");
        assertTrue(defaults.values().stream().noneMatch(value -> value < 0),
                "这些键没写成 ${ENV:默认} 形态（写死数字后环境变量再也覆盖不了）：" + defaults);
        return defaults;
    }

    private Path locateDoc() {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("../docs/05-接口设计说明书.md").normalize();
        assertTrue(Files.exists(fromServer), "找不到 docs/05-接口设计说明书.md（门禁必须在仓库内运行）");
        return fromServer;
    }

    private record DocRow(String paths, int defaultLimit, String propertyKey) { }
}
