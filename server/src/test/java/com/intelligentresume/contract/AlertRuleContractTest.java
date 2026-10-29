package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警链路的静态契约门禁（第四十一批取证）。
 *
 * <p>背景：监控交付物是本仓唯一「坏了不会被测试发现」的部分——规则文件写错一个指标名、
 * 标签值、job 名或 severity，Prometheus 照常加载、告警却再也不会触发（或永远触发）。实测取证：
 * <ul>
 *   <li>{@code monitoring/prometheus/rules/intelligent-resume-alerts.yml} 原有 11 条规则
 *       **全部**建立在应用自身导出的指标上；应用不可用时这些序列随抓取失败一起消失
 *       （{@code rate}/{@code histogram_quantile} 在空区间返回空向量、gauge 走 staleness），
 *       于是「整机不可用」这一最该告警的事件**零告警**；</li>
 *   <li>{@code docs/08} §7.1 承诺「API 健康检查：连续 3 次失败触发告警」「p95 超过 500ms 警告、
 *       超过 2s 严重」「5xx 超过 1% 警告、超过 5% 严重」，而实现里只有 2s / 1% 的单级规则，
 *       §7.3 还写着「评估 7 条告警规则」（实际 11 条）。</li>
 * </ul>
 *
 * <p>本门禁把上述契约机器化：指标名必须与应用注册的一致（改名即红）、可用性告警必须用
 * 抓取配置里的 job 名且容忍 ≥3 次抓取失败、severity 必须有 Alertmanager 路由匹配、
 * §7.1 承诺的分级阈值必须都在。
 */
class AlertRuleContractTest {

    private static final String RULES = "monitoring/prometheus/rules/intelligent-resume-alerts.yml";
    private static final String PROMETHEUS = "monitoring/prometheus/prometheus.yml";
    private static final String DEPLOY_PROMETHEUS = "deploy/prometheus/prometheus.yml";
    private static final String DEPLOY_REMOTE = "scripts/deploy-direct.remote.sh";
    /** 服务器上应用根的绝对路径；rule_files 必须以它开头，且去掉它之后与仓库布局同构。 */
    private static final String SERVER_PREFIX = "/opt/intelligent-resume/";
    private static final String ALERTMANAGER = "monitoring/alertmanager/alertmanager.yml";
    private static final String DASHBOARD = "monitoring/grafana/dashboards/intelligent-resume-operations.json";
    private static final String OBSERVABILITY =
            "server/src/main/java/com/intelligentresume/common/observability/AppObservability.java";

    /** Spring Boot 自带指标，不来自 AppObservability。 */
    private static final Set<String> FRAMEWORK_METRICS = Set.of("http_server_requests_seconds");

    /** Micrometer 名称 → Prometheus 名称的常见后缀（剥离后应等于注册名）。 */
    private static final List<String> SUFFIXES = List.of(
            "_seconds_bucket", "_seconds_count", "_seconds_sum", "_seconds",
            "_total", "_bucket", "_count", "_sum", "_max", "_min");

    private static final String LAST_GRADE_NOTE =
            "（docs/08 §7.1 的分级阈值承诺与该规则集必须一致，删掉任一级都会让文档失真）";

    @Test
    @DisplayName("告警规则引用的指标都必须由 AppObservability 注册（改名即静默失效，必须是红）")
    void alertRulesOnlyReferenceRegisteredMetrics() throws Exception {
        Set<String> registered = registeredMetricNames();
        assertTrue(registered.size() >= 15, "从 " + OBSERVABILITY + " 解析到的指标名过少（"
                + registered.size() + "），门禁可能失效");

        Set<String> unknown = new TreeSet<>();
        for (Map<String, Object> rule : rules()) {
            for (String metric : metricTokens(String.valueOf(rule.get("expr")))) {
                if (!isRegistered(metric, registered)) {
                    unknown.add(metric + "（用于 " + rule.get("alert") + "）");
                }
            }
        }
        assertTrue(unknown.isEmpty(), RULES + " 引用了未注册的指标：\n  " + String.join("\n  ", unknown)
                + "\n这类漂移不会让 Prometheus 报错，只会让该告警永远不触发");
    }

    @Test
    @DisplayName("Grafana 面板引用的指标同样必须已注册（改名后面板静默空白）")
    void dashboardOnlyReferencesRegisteredMetrics() throws Exception {
        Set<String> registered = registeredMetricNames();
        Set<String> unknown = new TreeSet<>();
        for (String expr : jsonStringValues(read(DASHBOARD), "expr")) {
            for (String metric : metricTokens(expr)) {
                if (!isRegistered(metric, registered)) {
                    unknown.add(metric);
                }
            }
        }
        assertTrue(unknown.isEmpty(), DASHBOARD + " 引用了未注册的指标：" + unknown);
    }

    @Test
    @DisplayName("可用性告警用抓取配置里的 job 名，且容忍至少 3 次抓取失败")
    void availabilityAlertMatchesScrapeJobAndToleratesThreeFailures() throws Exception {
        Set<String> scrapeJobs = new LinkedHashSet<>(matches(read(PROMETHEUS), "job_name:\\s*(\\S+)"));
        assertTrue(scrapeJobs.size() == 1, PROMETHEUS + " 期望恰好一个 job，实际 " + scrapeJobs);

        long scrapeIntervalSeconds = seconds(matches(read(PROMETHEUS), "scrape_interval:\\s*(\\S+)").get(0));

        List<Map<String, Object>> availabilityRules = new ArrayList<>();
        Set<String> referencedJobs = new LinkedHashSet<>();
        for (Map<String, Object> rule : rules()) {
            List<String> jobs = matches(String.valueOf(rule.get("expr")), "up\\{job=\"([^\"]+)\"\\}");
            if (!jobs.isEmpty()) {
                availabilityRules.add(rule);
                referencedJobs.addAll(jobs);
            }
        }
        assertTrue(!availabilityRules.isEmpty(), RULES + " 缺少可用性告警：其余规则全部建立在应用自身指标上，"
                + "而应用不可用时这些序列会随抓取失败一起消失（rate/histogram_quantile 在空区间返回空向量），"
                + "「整机不可用」将没有任何告警——必须有一条基于 Prometheus 自身 `up` 的规则");
        assertTrue(referencedJobs.equals(scrapeJobs), "可用性告警引用的 job 与 "
                + PROMETHEUS + " 的 job_name 不一致：" + referencedJobs + " vs " + scrapeJobs
                + "（job 名写错时 up 序列为空，告警同样静默失效）");

        long minSeconds = 3 * scrapeIntervalSeconds;
        for (Map<String, Object> rule : availabilityRules) {
            long forSeconds = seconds(String.valueOf(rule.get("for")));
            assertTrue(forSeconds >= minSeconds, rule.get("alert") + " 的 for=" + rule.get("for")
                    + " 不足 3 个抓取周期（scrape_interval=" + scrapeIntervalSeconds + "s ⇒ 至少 "
                    + minSeconds + "s）：docs/08 §7.1 承诺「连续 3 次失败触发告警」，"
                    + "过短会在单次抓取抖动时误报");
        }
    }

    @Test
    @DisplayName("每条告警的 severity 都必须有 Alertmanager 路由匹配（否则静默落到默认接收器）")
    void everySeverityIsRouted() throws Exception {
        Set<String> routed = new LinkedHashSet<>(matches(read(ALERTMANAGER), "severity=\"(\\w+)\""));
        assertTrue(!routed.isEmpty(), ALERTMANAGER + " 未解析到任何 severity 匹配器");

        Set<String> unrouted = new TreeSet<>();
        for (Map<String, Object> rule : rules()) {
            String severity = String.valueOf(asMap(rule.get("labels")).get("severity"));
            if (!routed.contains(severity)) {
                unrouted.add(severity + "（用于 " + rule.get("alert") + "）");
            }
        }
        assertTrue(unrouted.isEmpty(), RULES + " 存在无路由匹配的 severity：" + unrouted
                + "；" + ALERTMANAGER + " 已匹配的取值：" + routed + "。未匹配的告警会落到默认接收器，"
                + "critical 的邮件升级会被静默跳过");
    }

    @Test
    @DisplayName("docs/08 §7.1 承诺的分级阈值（5xx 1%/5%、p95 500ms/2s）都必须有对应规则")
    void documentedThresholdGradesAreImplemented() throws Exception {
        assertGrades("http_server_requests_seconds_count{status=~\"5..\"}", "> 0.01", "> 0.05");
        assertGrades("histogram_quantile(0.95", "> 0.5", "> 2");
    }

    @Test
    @DisplayName("deploy 份抓取配置必须加载规则源（rule_files 为空 = 15 条规则从未被评估）")
    void deployScrapeConfigAlsoLoadsRules() throws Exception {
        // 必须单独实现，不能复用 availabilityAlertMatchesScrapeJobAndToleratesThreeFailures 的
        // "恰好 1 个 job" 断言：deploy 份有 2 个 job（api + self），结构不同，复用必炸。
        Object ruleFilesObj = loadYaml(DEPLOY_PROMETHEUS).get("rule_files");
        assertTrue(ruleFilesObj instanceof List && !((List<?>) ruleFilesObj).isEmpty(),
                DEPLOY_PROMETHEUS + " 缺少 rule_files —— 直连路线的 15 条告警规则将**从未被评估**："
                        + "「规则写对」「门禁绿」「真实环境在评估」是三件事（2026-10-28 取证）");

        Set<String> loaded = new TreeSet<>();
        for (Object patternObj : (List<?>) ruleFilesObj) {
            String pattern = String.valueOf(patternObj);
            assertTrue(pattern.startsWith(SERVER_PREFIX),
                    "rule_files 必须用绝对路径（prometheus 以 User=prometheus 运行，工作目录不确定）：" + pattern);
            // 服务器路径与仓库布局同构（/opt/intelligent-resume/monitoring/prometheus/rules/
            // ↔ 仓库 monitoring/prometheus/rules/），去掉前缀即可机械映射回仓库相对路径。
            String repoGlob = pattern.substring(SERVER_PREFIX.length());
            assertTrue(repoGlob.endsWith("*.yml"),
                    "门禁只展开 *.yml 结尾的 glob，其他形态需先扩展本断言：" + pattern);
            String dir = repoGlob.substring(0, repoGlob.lastIndexOf('/'));
            try (var stream = Files.newDirectoryStream(repoFile(dir), "*.yml")) {
                for (Path file : stream) {
                    String relative = dir + "/" + file.getFileName();
                    Object groups = loadYaml(relative).get("groups");
                    assertTrue(groups instanceof List, relative + " 缺少 groups 段");
                    for (Object group : (List<?>) groups) {
                        Object groupRules = asMap(group).get("rules");
                        assertTrue(groupRules instanceof List, relative + " 的 group 缺少 rules 段");
                        for (Object rule : (List<?>) groupRules) {
                            loaded.add(String.valueOf(asMap(rule).get("alert")));
                        }
                    }
                }
            }
        }
        assertTrue(!loaded.isEmpty(), "rule_files 展开后没有加载到任何告警规则 —— 路径写错时 glob 静默为空，"
                + "Prometheus 不报错，规则从未被评估");

        Set<String> source = new TreeSet<>();
        for (Map<String, Object> rule : rules()) {
            source.add(String.valueOf(rule.get("alert")));
        }
        assertTrue(loaded.equals(source),
                "deploy 份 rule_files 加载到的告警集与规则源深度不相等：加载 " + loaded + " vs 源 " + source
                        + "（多 = 源里有规则没被加载；少 = 加载了别的东西。两者都意味着线上评估的不是这份源）");
    }

    @Test
    @DisplayName("部署脚本必须同步规则目录并重启 prometheus（否则规则改动永远停在仓库）")
    void deployScriptSyncsRuleDirectory() throws Exception {
        // 抹白注释是必须的：remote.sh 有大段注释在讲"这些文件不随部署同步"，不过滤会假绿
        String script = read(DEPLOY_REMOTE).replaceAll("(?m)^\\s*#.*$", "");
        assertTrue(script.contains("monitoring/prometheus/rules"),
                DEPLOY_REMOTE + " 没有同步规则目录 —— 15 条规则的任何改动都只停留在仓库，线上继续评估旧规则");
        assertTrue(Pattern.compile("install -d [^\\n]*monitoring/prometheus/rules").matcher(script).find(),
                "缺少规则目录创建（install -d）—— 目录不存在时同步会整体失败");
        assertTrue(Pattern.compile("install -m 644 [^\\n]*rule_tmp").matcher(script).find(),
                "缺少规则文件安装（install -m 644 ... $rule_tmp）—— 没有逐文件落位就不是同步");
        assertTrue(script.contains("intelligent-resume-prometheus")
                        && (script.contains("restart intelligent-resume-prometheus")
                            || script.contains("enable --now intelligent-resume-prometheus")),
                "缺少 prometheus 重启分支 —— 规则文件同步了但不重启也不会加载（Prometheus 只在启动时读 rule_files）");
    }

    private void assertGrades(String exprMarker, String warningThreshold, String criticalThreshold) throws Exception {
        Set<String> severities = new TreeSet<>();
        boolean warning = false;
        boolean critical = false;
        for (Map<String, Object> rule : rules()) {
            String expr = String.valueOf(rule.get("expr"));
            if (!expr.contains(exprMarker)) {
                continue;
            }
            String severity = String.valueOf(asMap(rule.get("labels")).get("severity"));
            severities.add(severity);
            warning |= "warning".equals(severity) && expr.contains(warningThreshold);
            critical |= "critical".equals(severity) && expr.contains(criticalThreshold);
        }
        assertTrue(warning, "缺少 `" + exprMarker + "` 的警告级规则（阈值 " + warningThreshold + "）" + LAST_GRADE_NOTE
                + "，当前该指标只有：" + severities);
        assertTrue(critical, "缺少 `" + exprMarker + "` 的严重级规则（阈值 " + criticalThreshold + "）" + LAST_GRADE_NOTE
                + "，当前该指标只有：" + severities);
    }

    // ── 解析工具 ────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> rules() throws Exception {
        Object groups = loadYaml(RULES).get("groups");
        assertTrue(groups instanceof List, RULES + " 缺少 groups 段");
        List<Map<String, Object>> collected = new ArrayList<>();
        for (Object group : (List<?>) groups) {
            Object groupRules = asMap(group).get("rules");
            assertTrue(groupRules instanceof List, RULES + " 的 group 缺少 rules 段");
            for (Object rule : (List<?>) groupRules) {
                collected.add(asMap(rule));
            }
        }
        // 下限刻意取「修复前规则数以下」（第四十批修复后为 15、修复前为 11）：门禁只要不为空转即可，
        // 若把下限设成修复后的数量，红判定会退化成「规则数量不足」而不是缺哪条语义规则。
        assertTrue(collected.size() >= 10, RULES + " 解析到的规则过少（" + collected.size() + "），门禁可能失效");
        return collected;
    }

    /** 从 AppObservability 源码提取 `builder("resume_x")` 中的注册名。 */
    private Set<String> registeredMetricNames() throws Exception {
        Set<String> names = new TreeSet<>(matches(read(OBSERVABILITY), "builder\\(\"(resume_[a-z0-9_]+)\""));
        assertFalse(names.isEmpty(), OBSERVABILITY + " 未解析到任何指标注册名");
        return names;
    }

    /** 提取表达式中的指标 token，并把 Prometheus 后缀还原成 Micrometer 注册名。 */
    private Set<String> metricTokens(String expr) {
        return new TreeSet<>(matches(expr, "(resume_[a-z0-9_]+)"));
    }

    /**
     * token 是否对应某个已注册指标。
     *
     * <p>先按原名匹配（存在注册名本身以 {@code _seconds} 结尾的 gauge，如
     * {@code resume_ai_queue_oldest_pending_seconds}），再逐个剥离 Prometheus 后缀重试
     * （counter 的 {@code _total}、timer 的 {@code _seconds_bucket} 等）。
     */
    private boolean isRegistered(String token, Set<String> registered) {
        String candidate = token;
        while (true) {
            if (registered.contains(candidate) || FRAMEWORK_METRICS.contains(candidate)) {
                return true;
            }
            String stripped = candidate;
            for (String suffix : SUFFIXES) {
                if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                    stripped = candidate.substring(0, candidate.length() - suffix.length());
                    break;
                }
            }
            if (stripped.equals(candidate)) {
                return false;
            }
            candidate = stripped;
        }
    }

    /** 取 JSON 文本中指定键的字符串取值（本门禁只读自家产物，不引入 JSON 解析依赖）。 */
    private List<String> jsonStringValues(String json, String key) {
        return matches(json, "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
    }

    private List<String> matches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** 解析 Prometheus 时长（45s / 10m / 1h）。 */
    private long seconds(String duration) {
        Matcher matcher = Pattern.compile("^(\\d+)([smh])$").matcher(duration.trim());
        assertTrue(matcher.matches(), "无法解析时长：" + duration);
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> value;
            case "m" -> value * 60;
            default -> value * 3600;
        };
    }

    private Map<String, Object> loadYaml(String relative) throws Exception {
        try (InputStream input = Files.newInputStream(repoFile(relative))) {
            Object loaded = new Yaml().load(input);
            assertTrue(loaded instanceof Map, relative + " 顶层应为映射");
            return asMap(loaded);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private String read(String relative) throws Exception {
        return SourceText.read(repoFile(relative));
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
