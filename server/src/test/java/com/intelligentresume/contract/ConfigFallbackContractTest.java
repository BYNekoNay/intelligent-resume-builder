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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置兜底一致性门禁（静态）：每个 {@code @Value("${key:fallback}")} 的兜底值必须与
 * {@code application.yml} 中同一 key 的默认值一致。
 *
 * <p>背景（第三十批取证）：同一配置项在三处出现——代码兜底、yml 默认值、环境变量默认——
 * 任一漂移都是「配置缺省时行为与文档/预期不同」的静默陷阱。此前 {@code PdfDeadlineContractTest}
 * 只覆盖 PDF 两个键；本门禁用 YAML 解析器把**全部** {@code @Value} 兜底纳入比对，
 * 避免漏网（首跑即暴露 {@code app.ai.bailian.read-timeout-seconds} 等漂移）。
 *
 * <p>比对规则：yml 中声明了该 key 且取值为标量（含 {@code ${ENV:default}} 形式）时，
 * 其默认值必须与代码兜底字符串一致；仅代码兜底（yml 未声明）或非标量（列表/对象）跳过。
 */
class ConfigFallbackContractTest {

    /** 匹配 {@code @Value("${key:fallback}")}（key 与 fallback 均允许跨行外的任意非 } 字符）。 */
    private static final Pattern VALUE_ANNOTATION =
            Pattern.compile("@Value\\(\"\\$\\{([^:}]+):([^}]*)\\}\"\\)");

    /** yml 中的 {@code ${ENV:default}} 占位形式。 */
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:(.*)\\}$");

    @Test
    @DisplayName("代码 @Value 兜底与 application.yml 默认值逐项一致")
    void codeFallbacksMatchYamlDefaults() throws Exception {
        Map<String, Object> yaml = loadYaml("server/src/main/resources/application.yml");

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        int total = 0;
        for (Path file : javaSources("server/src/main/java")) {
            String source = SourceText.read(file);
            Matcher matcher = VALUE_ANNOTATION.matcher(source);
            while (matcher.find()) {
                total++;
                String key = matcher.group(1);
                String fallback = matcher.group(2);
                Object yamlValue = resolve(yaml, key);
                if (yamlValue == null) continue;
                String yamlDefault = scalarDefault(yamlValue);
                if (yamlDefault == null) continue;
                compared++;
                if (!normalize(fallback).equals(normalize(yamlDefault))) {
                    mismatches.add("  " + key + " → 代码兜底 [" + fallback + "] vs yml 默认 [" + yamlDefault + "]");
                }
            }
        }

        assertTrue(total >= 40,
                "扫描到的 @Value 兜底过少（" + total + "），门禁可能未生效（源文件解析异常？）");
        assertTrue(compared >= 25,
                "与 yml 可比对的配置项过少（" + compared + "），门禁可能失效（YAML 解析异常？）");
        assertTrue(mismatches.isEmpty(),
                "以下配置项的代码兜底与 application.yml 默认值不一致：配置缺省时会静默采用不同行为，"
                        + "须统一（任选一侧为准，但要一致）：\n" + String.join("\n", mismatches));
    }

    /** {@code @ConfigurationProperties(prefix = "P")} 类声明。 */
    private static final Pattern CONFIG_PROPERTIES_CLASS =
            Pattern.compile("@ConfigurationProperties\\(prefix\\s*=\\s*\"([^\"]+)\"\\)");

    /** 数值/布尔字段及其字面量初始值（String/集合字段跳过：转义与字面差异会误报）。 */
    private static final Pattern NUMERIC_FIELD =
            Pattern.compile("private\\s+(int|long|boolean)\\s+([a-zA-Z][A-Za-z0-9_]*)\\s*=\\s*([0-9]+|true|false)\\s*;");

    /**
     * 与 {@code @Value} 兜底同理：{@code @ConfigurationProperties} 的数值/布尔字段默认值也必须与
     * yml 默认值一致。第五十三批补 —— 此前本门禁只扫 {@code @Value}，绑定路径**完全无守护**，
     * 因此 {@code AiTaskWorkerProperties.leaseSeconds} 的字段默认值停留在 180，
     * 而 yml 早已是 {@code ${AI_WORKER_LEASE_S:660}}（第一批 #60 把租约 180→660 时漏改此处）。
     */
    @Test
    @DisplayName("@ConfigurationProperties 的数值/布尔字段默认值与 application.yml 默认值一致")
    void configurationPropertiesDefaultsMatchYaml() throws Exception {
        Map<String, Object> yaml = loadYaml("server/src/main/resources/application.yml");

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (Path file : javaSources("server/src/main/java")) {
            String source = SourceText.read(file);
            Matcher classMatcher = CONFIG_PROPERTIES_CLASS.matcher(source);
            if (!classMatcher.find()) continue;
            String prefix = classMatcher.group(1);
            Matcher fieldMatcher = NUMERIC_FIELD.matcher(source);
            while (fieldMatcher.find()) {
                String field = fieldMatcher.group(2);
                String initial = fieldMatcher.group(3);
                String key = prefix + "." + kebab(field);
                Object yamlValue = resolve(yaml, key);
                if (yamlValue == null) continue;
                String yamlDefault = scalarDefault(yamlValue);
                if (yamlDefault == null) continue;
                compared++;
                if (!normalize(initial).equals(normalize(yamlDefault))) {
                    mismatches.add("  " + key + " → 字段默认 [" + initial + "] vs yml 默认 [" + yamlDefault + "]");
                }
            }
        }

        assertTrue(compared >= 5,
                "可比对的 @ConfigurationProperties 数值字段过少（" + compared + "），门禁可能失效");
        assertTrue(mismatches.isEmpty(),
                "以下 @ConfigurationProperties 字段默认值与 application.yml 不一致：该键在 yml 存在时以 yml 为准，"
                        + "仅当 yml 缺键时才用字段默认值 —— 两处不一致即「配置缺省时静默采用另一个值」的陷阱：\n"
                        + String.join("\n", mismatches));
    }

    /** camelCase → kebab-case（字段名反推配置键）。 */
    private String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private Map<String, Object> loadYaml(String relative) throws Exception {
        Path target = repoFile(relative);
        try (InputStream input = Files.newInputStream(target)) {
            Object loaded = new Yaml().load(input);
            assertTrue(loaded instanceof Map, relative + " 顶层应为映射");
            return asMap(loaded);
        }
    }

    /** 按点号路径解析嵌套 yml 映射；不存在时返回 null。 */
    private Object resolve(Map<String, Object> root, String dottedKey) {
        Object current = root;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = asMap(current).get(segment);
            if (current == null) return null;
        }
        return current;
    }

    /** 标量默认值：{@code ${ENV:default}} 取 default，普通标量取自身；非标量返回 null。 */
    private String scalarDefault(Object value) {
        if (value instanceof Map || value instanceof List) return null;
        String text = String.valueOf(value).trim();
        Matcher placeholder = ENV_PLACEHOLDER.matcher(text);
        return placeholder.matches() ? placeholder.group(1) : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String normalize(String value) {
        return value.trim().replaceAll("^\"|\"$", "");
    }

    private List<Path> javaSources(String relativeDir) throws Exception {
        try (Stream<Path> paths = Files.walk(repoFile(relativeDir))) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件/目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }

    /** 保留声明顺序的映射（便于阅读失败信息）。 */
    @SuppressWarnings("unused")
    private Map<String, Object> ordered() {
        return new LinkedHashMap<>();
    }
}
