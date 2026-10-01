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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置消费点门禁（静态）：{@code application.yml} 中每个 {@code app.*} **标量叶子键**都必须有消费点。
 *
 * <p>背景（第五十三批全量对账）：配置有三种失效形态，前两种已有门禁，第三种此前**完全无守护**——
 * <ol>
 *   <li>「代码 {@code @Value} 兜底 vs yml 默认」漂移 → {@link ConfigFallbackContractTest}；</li>
 *   <li>「{@code @ConfigurationProperties} 字段默认 vs yml 默认」漂移 → 同门禁（第五十三批补）；</li>
 *   <li><b>「声明了但没有任何消费点」</b>（本门禁）——键写在 yml 里、也会被 Spring 读进环境，
 *       但没有任何代码读它，于是<b>改它不生效</b>却让运维以为可配。第五十二批的
 *       {@code app.pdf.max-output-bytes}、第五十三批的 {@code app.ai.quota.JOB_MATERIAL_SELECTION}
 *       与 {@code app.job.parser.rule-version} 都属此类。</li>
 * </ol>
 *
 * <p>消费点判定（任一成立即算已消费）：
 * <ol>
 *   <li>main 代码（{@code server/src/main} 下的 java/yml/properties）出现该**点号路径字面量**
 *       （如 {@code @Value("${app.pdf.max-output-bytes:...}")}）；</li>
 *   <li>落在某个 {@code @ConfigurationProperties(prefix="P")} 类之下，且相对路径的**首段**
 *       camel 化后是该类的字段名（覆盖 {@code prefix.some-key} 与 Map 元素 {@code prefix.some-map.k}）。</li>
 * </ol>
 *
 * <p>「声明了但确实尚未实现」的键必须显式登记进 {@link #KNOWN_UNCONSUMED}（附理由），
 * 且一旦它获得消费点，门禁会要求从白名单移除（防白名单腐化）。
 *
 * <p>注：与 {@link ConfigFallbackContractTest} 一致，**列表/映射值不作为叶子**
 * （配置为对象/数组的键由各自的功能测试守护，如 {@code ScoringDictionaryBindingIT}）。
 */
class ConfigConsumerContractTest {

    /**
     * 已知「声明了但当前没有消费点」的键 → 理由/处置。
     * 这不是"允许存在死键"，而是把死键**显式登记**，使其不能被静默新增。
     */
    private static final Map<String, String> KNOWN_UNCONSUMED = new LinkedHashMap<>();

    static {
        KNOWN_UNCONSUMED.put("app.job.jd-text.min-length",
                "T05 设计意图为「JD 文本短于该值视为空、允许解析但关键词为空」，解析器未实现；"
                        + "是否实施属产品口径，见 docs/decisions/OPEN-DECISIONS.md（第五十三批登记）");
        KNOWN_UNCONSUMED.put("app.ai.confirmation.require-explicit-source",
                "T08 设计项未实现（当前行为由 DTO/服务固化），见 OPEN-DECISIONS.md（第五十三批登记）");
        KNOWN_UNCONSUMED.put("app.ai.confirmation.allow-user-new-fact",
                "T08 设计项未实现，见 OPEN-DECISIONS.md（第五十三批登记）");
        KNOWN_UNCONSUMED.put("app.ai.confirmation.max-confirmed-items",
                "上限已由 ConfirmRequest 的 @Size(max = 200) 硬编码实现，配置化未实现，"
                        + "见 OPEN-DECISIONS.md（第五十三批登记）");
    }

    private static final Pattern CONFIG_PROPERTIES =
            Pattern.compile("@ConfigurationProperties\\(prefix\\s*=\\s*\"([^\"]+)\"\\)");

    /** 字段声明（含多词泛型类型）；组 2 为字段名。 */
    private static final Pattern PLAIN_FIELD =
            Pattern.compile("private\\s+[\\w<>,\\[\\].\\s]+?\\s+([a-zA-Z][A-Za-z0-9_]*)\\s*(?:=|;)");

    @Test
    @DisplayName("application.yml 的每个 app.* 标量键都有消费点（否则已显式登记为未实现）")
    void everyAppKeyHasConsumer() throws Exception {
        Map<String, Object> yaml = loadYaml("server/src/main/resources/application.yml");

        List<String> leaves = new ArrayList<>();
        collectScalarLeaves(yaml, "", leaves);

        List<String> corpus = readCorpus();
        Map<String, Set<String>> bindings = readBindings();

        List<String> unconsumed = new ArrayList<>();
        int consumed = 0;
        for (String path : leaves) {
            if (!path.startsWith("app.")) continue;
            if (isConsumed(path, corpus, bindings)) {
                consumed++;
            } else if (!KNOWN_UNCONSUMED.containsKey(path)) {
                unconsumed.add("  " + path);
            }
        }

        int appLeaves = (int) leaves.stream().filter(p -> p.startsWith("app.")).count();
        assertTrue(appLeaves >= 70,
                "扫描到的 app.* 标量键过少（" + appLeaves + "），门禁可能未生效（YAML 解析异常？）");
        assertTrue(consumed >= 65,
                "判定为「有消费点」的键过少（" + consumed + "），门禁可能失效");

        assertTrue(unconsumed.isEmpty(),
                "以下 app.* 配置键在 application.yml 声明，但**没有任何消费点**（声明即虚构：改它不生效，"
                        + "却让运维以为可配）。处置：实现消费点 / 移除该键 / 登记进 KNOWN_UNCONSUMED（须附理由）：\n"
                        + String.join("\n", unconsumed));

        // 白名单不得腐化：已登记为「未实现」的键一旦获得消费点，必须从白名单移除
        List<String> stale = new ArrayList<>();
        for (String path : KNOWN_UNCONSUMED.keySet()) {
            if (isConsumed(path, corpus, bindings)) {
                stale.add("  " + path);
            }
        }
        assertTrue(stale.isEmpty(),
                "以下键已登记为「未实现」，但现在**已有消费点** —— 请从 KNOWN_UNCONSUMED 移除"
                        + "（白名单腐化会让门禁失去意义）：\n" + String.join("\n", stale));
    }

    /** 字面量命中 或 落在某个 @ConfigurationProperties 前缀的字段（含 Map 元素）之下。 */
    private boolean isConsumed(String path, List<String> corpus, Map<String, Set<String>> bindings) {
        if (corpus.stream().anyMatch(text -> text.contains(path))) {
            return true;
        }
        for (Map.Entry<String, Set<String>> entry : bindings.entrySet()) {
            String prefix = entry.getKey();
            if (!path.equals(prefix) && !path.startsWith(prefix + ".")) continue;
            String relative = path.substring(prefix.length()).replaceFirst("^\\.", "");
            if (relative.isEmpty()) continue;
            String firstSegment = relative.split("\\.")[0];
            if (entry.getValue().contains(camel(firstSegment))) {
                return true;
            }
        }
        return false;
    }

    private void collectScalarLeaves(Object node, String prefix, List<String> out) {
        if (!(node instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map) {
                collectScalarLeaves(value, path, out);
            } else if (!(value instanceof List)) {
                out.add(path);
            }
        }
    }

    /** 收集 @ConfigurationProperties 前缀 → 字段名集合。 */
    private Map<String, Set<String>> readBindings() throws Exception {
        Map<String, Set<String>> bindings = new LinkedHashMap<>();
        for (Path file : sourceFiles("server/src/main/java", ".java")) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = CONFIG_PROPERTIES.matcher(source);
            if (!matcher.find()) continue;
            Set<String> fields = new LinkedHashSet<>();
            Matcher fieldMatcher = PLAIN_FIELD.matcher(source);
            while (fieldMatcher.find()) {
                fields.add(fieldMatcher.group(1));
            }
            bindings.put(matcher.group(1), fields);
        }
        return bindings;
    }

    private List<String> readCorpus() throws Exception {
        List<String> texts = new ArrayList<>();
        for (Path file : sourceFiles("server/src/main", ".java", ".yml", ".yaml", ".properties")) {
            texts.add(Files.readString(file, StandardCharsets.UTF_8));
        }
        return texts;
    }

    private List<Path> sourceFiles(String relativeDir, String... extensions) throws Exception {
        try (Stream<Path> paths = Files.walk(repoFile(relativeDir))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.toString();
                        for (String extension : extensions) {
                            if (name.endsWith(extension)) return true;
                        }
                        return false;
                    })
                    .toList();
        }
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
        return (Map<String, Object>) value;
    }

    /** kebab-case → camelCase（字段名反推，用于 Map/嵌套键）。 */
    private String camel(String kebab) {
        String[] parts = kebab.split("-");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return result.toString();
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件/目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
