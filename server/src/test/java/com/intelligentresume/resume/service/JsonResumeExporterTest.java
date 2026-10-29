package com.intelligentresume.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Resume 导出映射单测。
 *
 * <p>重点不是「字段有没有」，而是三条容易悄悄坏掉的性质：
 * 1. 根节点只出现标准键（官方 schema 根上是 {@code additionalProperties: false}，多一个就整份校验失败）；
 * 2. 自由文本时间**不被伪造**成结构化日期（宁可省略，也不能编一个机器可读日期出来）；
 * 3. 平台私有整块内容进 {@code meta.platform}，未知键进 {@code meta.platform.unknownSections}，一个字都不丢。
 */
class JsonResumeExporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonResumeExporter exporter =
            new JsonResumeExporter(new JsonResumeFieldMapper(), objectMapper);

    @Test
    @DisplayName("空简历也产出合法骨架：只有 $schema 与 meta")
    void emptyResumeKeepsValidSkeleton() {
        Map<String, Object> out = exporter.export(null, 9L, 2);

        assertEquals(JsonResumeExporter.SCHEMA_URL, out.get("$schema"));
        assertTrue(out.keySet().stream().allMatch(JsonResumeExporter.STANDARD_ROOT_KEYS::contains),
                "出现了非标准根键：" + out.keySet());
        assertTrue(out.containsKey("meta"));
        assertFalse(out.containsKey("basics"), "没有基础信息时不应塞空对象");
    }

    @Test
    @DisplayName("basics：title→label，location 字符串→city，links→profiles")
    void mapsBasicsAndLinks() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("basics", Map.of("name", "张三", "title", "后端工程师", "email", "z@example.com",
                "location", "成都", "summary", "七年 Java"));
        resume.put("links", List.of(Map.of("label", "GitHub", "url", "https://github.com/x")));

        Map<String, Object> basics = section(exporter.export(resume, 1L, 1), "basics");

        assertEquals("后端工程师", basics.get("label"));
        assertEquals("成都", section(basics, "location").get("city"));
        assertEquals(List.of(Map.of("network", "GitHub", "url", "https://github.com/x")), basics.get("profiles"));
    }

    @Test
    @DisplayName("work：company→name、period 原样保留、非标准日期被丢弃、亮点两种形态都归一")
    void mapsWorkWithoutFakingDates() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("work", List.of(
                Map.of("company", "某厂", "position", "工程师",
                        "startDate", "2021-03", "endDate", "至今", "period", "2021 - 至今",
                        "highlights", List.of(Map.of("text", "重构订单链路"), "带 3 人小组")),
                Map.of("company", "另一厂", "position", "实习生", "startDate", "2020年7月")));

        List<Map<String, Object>> work = list(exporter.export(resume, 1L, 1), "work");

        assertEquals("某厂", work.get(0).get("name"));
        assertEquals("2021-03", work.get(0).get("startDate"));
        assertFalse(work.get(0).containsKey("endDate"), "「至今」不是 iso8601，不能硬写进 endDate");
        assertEquals("2021 - 至今", work.get(0).get("period"), "原始表述必须保住");
        assertEquals(List.of("重构订单链路", "带 3 人小组"), work.get(0).get("highlights"));
        assertFalse(work.get(1).containsKey("startDate"), "「2020年7月」不是 iso8601，应省略而不是改造");
    }

    @Test
    @DisplayName("volunteering→volunteer、education、awards、certificates、publications、languages 各自改名")
    void mapsRemainingCollections() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("volunteering", List.of(Map.of("organization", "公益组织", "role", "讲师")));
        resume.put("education", List.of(Map.of("school", "某大学", "degree", "本科", "major", "软件工程")));
        resume.put("awards", List.of(Map.of("name", "优秀员工", "issuer", "某厂", "date", "2023-05")));
        resume.put("certificates", List.of(Map.of("name", "软件设计师", "issuer", "工信部")));
        resume.put("publications", List.of(Map.of("title", "论文", "publisher", "某刊", "date", "2022")));
        resume.put("languages", List.of(Map.of("name", "日语", "level", "N2")));

        Map<String, Object> out = exporter.export(resume, 1L, 1);

        assertEquals("公益组织", list(out, "volunteer").get(0).get("organization"));
        Map<String, Object> edu = list(out, "education").get(0);
        assertEquals("某大学", edu.get("institution"));
        assertEquals("本科", edu.get("studyType"));
        assertEquals("软件工程", edu.get("area"));
        assertEquals("优秀员工", list(out, "awards").get(0).get("title"));
        assertEquals("某厂", list(out, "awards").get(0).get("awarder"));
        assertEquals("软件设计师", list(out, "certificates").get(0).get("name"));
        assertEquals("论文", list(out, "publications").get(0).get("name"));
        assertEquals("2022", list(out, "publications").get(0).get("releaseDate"));
        assertEquals("日语", list(out, "languages").get(0).get("language"));
        assertEquals("N2", list(out, "languages").get(0).get("fluency"));
    }

    @Test
    @DisplayName("skills/projects：逗号分隔的技术栈拆成标准关键词数组")
    void splitsKeywordText() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("skills", List.of(Map.of("name", "后端", "keywords", "Java, Spring Boot、MySQL")));
        resume.put("projects", List.of(Map.of("name", "智历", "technologies", "Vue3/TS;Node",
                "role", "负责人", "highlights", List.of("上线 3 个模块"))));

        Map<String, Object> out = exporter.export(resume, 1L, 1);

        assertEquals(List.of("Java", "Spring Boot", "MySQL"), list(out, "skills").get(0).get("keywords"));
        Map<String, Object> project = list(out, "projects").get(0);
        assertEquals(List.of("Vue3", "TS", "Node"), project.get("keywords"));
        assertEquals(List.of("负责人"), project.get("roles"));
    }

    @Test
    @DisplayName("平台私有整块内容进 meta.platform，未知键进 meta.platform.unknownSections")
    void keepsPlatformOnlySectionsInMeta() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("objective", Map.of("targetRole", "架构师"));
        resume.put("courses", List.of(Map.of("name", "课程")));
        resume.put("customSections", List.of(Map.of("title", "开源贡献")));
        resume.put("template", Map.of("code", "classic"));
        resume.put("layout", Map.of("sectionOrder", List.of("work")));
        resume.put("legacyField", List.of(Map.of("value", "历史残留")));

        Map<String, Object> out = exporter.export(resume, 42L, 7);
        Map<String, Object> platform = section(section(out, "meta"), "platform");

        assertTrue(platform.containsKey("objective"));
        assertTrue(platform.containsKey("courses"));
        assertTrue(platform.containsKey("customSections"));
        assertTrue(platform.containsKey("template"));
        assertTrue(platform.containsKey("layout"));
        assertEquals(42L, platform.get("resumeId"));
        assertEquals(7, platform.get("versionNo"));

        Map<String, Object> unknown = section(platform, "unknownSections");
        assertTrue(unknown.containsKey("legacyField"), "未知键必须保住，不能静默丢弃");
        assertFalse(out.containsKey("legacyField"), "未知键不能出现在根上（会破坏 schema 校验）");
        assertFalse(out.containsKey("objective"), "objective 没有标准归宿，不能放根上");
    }

    @Test
    @DisplayName("序列化结果是可读的缩进 JSON，文件名纯 ASCII 且不含用户输入")
    void serializesToReadableJson() {
        byte[] bytes = exporter.toJsonBytes(exporter.export(Map.of("basics", Map.of("name", "张三")), 3L, 5));
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(json.contains("\n  \"$schema\""), "应为 2 空格缩进：" + json.substring(0, 40));
        assertEquals("resume-3-v5-jsonresume.json", exporter.fileName(3L, 5));
        assertEquals("resume-3-jsonresume.json", exporter.fileName(3L, null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map, "缺少对象字段 " + key + "：" + parent.keySet());
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof List, "缺少数组字段 " + key + "：" + parent.keySet());
        return (List<Map<String, Object>>) value;
    }
}
