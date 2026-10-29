package com.intelligentresume.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ats.service.ParserViewSections;
import com.intelligentresume.resume.service.JsonResumeExporter;
import com.intelligentresume.resume.service.JsonResumeFieldMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Resume 导出契约门禁（B4）。
 *
 * <p>守四件事，每一条都对应一种「导出件看起来很对、实际不能用」的失败：
 * <ol>
 *   <li><b>根节点只能有标准键</b>：官方 schema 根上是 {@code additionalProperties: false}，
 *       多一个 {@code links} / {@code objective} 就让整份文档校验失败；</li>
 *   <li><b>不允许 null</b>：JSON Schema 里没有一处允许 null，出现 {@code "email": null} 即非法；</li>
 *   <li><b>不允许静默丢失</b>：夹具里每个字符串值（marker）都必须出现在导出结果里，
 *       哪怕它落在 {@code meta.platform.unknownSections}——「用户写过的字一个都不能少」；</li>
 *   <li><b>平台键与章节注册表同源</b>：{@code PLATFORM_KEYS} 必须等于
 *       {@code ParserViewSections.SECTION_KEYS} 加上 template/layout，避免两份清单各自演化。</li>
 * </ol>
 *
 * <p>夹具用仓库里真实的全字段样例 {@code test-fixtures/resume-all-sections.json}，
 * 而不是测试里手搓的小对象——手搓的夹具永远不会包含「我没见过的那种字段」。
 */
class JsonResumeExportContractTest {

    /** 官方 schema 的 properties（v1.0.0），写死在这里作为独立副本对拍。 */
    private static final Set<String> OFFICIAL_ROOT_KEYS = Set.of(
            "$schema", "basics", "work", "volunteer", "education", "awards", "certificates",
            "publications", "skills", "languages", "interests", "references", "projects", "meta");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonResumeExporter exporter =
            new JsonResumeExporter(new JsonResumeFieldMapper(), objectMapper);

    @Test
    @DisplayName("导出的根键全部落在官方 properties 内")
    void rootKeysStayStandard() throws Exception {
        Map<String, Object> exported = exportFixture();

        Set<String> extra = new TreeSet<>(exported.keySet());
        extra.removeAll(OFFICIAL_ROOT_KEYS);
        assertTrue(extra.isEmpty(), "导出件出现非标准根键（会让整份文档校验失败）：" + extra);

        assertEquals(OFFICIAL_ROOT_KEYS, JsonResumeExporter.STANDARD_ROOT_KEYS,
                "STANDARD_ROOT_KEYS 与官方 properties 不一致");
    }

    @Test
    @DisplayName("导出件不含 null（JSON Schema 没有一处允许 null）")
    void exportHasNoNullValues() throws Exception {
        List<String> paths = new ArrayList<>();
        collectNulls(exportFixture(), "", paths);
        assertTrue(paths.isEmpty(), "导出件出现 null 字段：" + paths);
    }

    @Test
    @DisplayName("夹具里的每个字符串值都能在导出件里找到（不静默丢内容）")
    void everyFixtureStringSurvives() throws Exception {
        Map<String, Object> fixture = readFixture();
        String exportedJson = new String(
                exporter.toJsonBytes(exporter.export(fixture, 1L, 1)), StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        collectStrings(fixture, values);
        for (String value : values) {
            // 只看有意义的文本：单字符/纯空白既可能是结构值也可能是噪声，误报成本高于收益
            if (value.isBlank() || value.length() < 3) continue;
            if (!exportedJson.contains(value)) missing.add(value);
        }
        assertTrue(!values.isEmpty(), "夹具里没有解析到任何字符串 —— 门禁锚点失配");
        assertTrue(missing.isEmpty(), "以下内容在导出件里丢失了：" + missing);
    }

    @Test
    @DisplayName("平台键集合 = 章节注册表 + template/layout（不许各写一份）")
    void platformKeysMatchSectionRegistry() {
        Set<String> expected = new LinkedHashSet<>(ParserViewSections.SECTION_KEYS);
        expected.add("template");
        expected.add("layout");
        assertEquals(expected, JsonResumeExporter.PLATFORM_KEYS,
                "导出器的平台键集合与章节注册表已漂移：新增章节必须同步导出侧");
    }

    // ------------------------------------------------------------ 工具

    private Map<String, Object> exportFixture() throws Exception {
        return exporter.export(readFixture(), 1L, 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFixture() throws Exception {
        Path path = repoFile("test-fixtures/resume-all-sections.json");
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), Map.class);
    }

    @SuppressWarnings("unchecked")
    private void collectNulls(Object node, String path, List<String> out) {
        if (node == null) {
            out.add(path.isEmpty() ? "$" : path);
            return;
        }
        if (node instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((key, value) -> collectNulls(value, path + "." + key, out));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectNulls(list.get(i), path + "[" + i + "]", out);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectStrings(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((key, value) -> collectStrings(value, out));
        } else if (node instanceof List<?> list) {
            for (Object entry : list) collectStrings(entry, out);
        } else if (node instanceof String text) {
            out.add(text);
        }
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
