package com.intelligentresume.ats.service;

import com.intelligentresume.ats.dto.ParserViewResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析器视角渲染器（B4）单测。
 *
 * <p>这一层的价值全在「确定性」：同一个 resumeJson 必须永远渲染出同一份文本，
 * 否则用户拿「解析器看到的」去和自己的编辑页对照时会对不上，诊断本身就失去意义。
 * 因此断言都落在**具体行内容**与**告警码**上，而不是「有没有渲染出东西」。
 */
class ResumeParserViewRendererTest {

    private final ResumeParserViewRenderer renderer = new ResumeParserViewRenderer();

    @Test
    @DisplayName("渲染出线性文本：页头 → 章节标题 → 条目字段 → 亮点")
    void rendersLinearText() {
        Map<String, Object> resume = resume(
                Map.of("name", "张三", "title", "后端工程师", "email", "zhang@example.com",
                        "phone", "13800138000", "location", Map.of("city", "成都"), "summary", "七年 Java 经验"),
                List.of(Map.of("company", "某厂", "position", "高级工程师",
                        "startDate", "2021-03", "endDate", "2024-06",
                        "highlights", List.of(Map.of("text", "主导订单链路重构")))));

        ParserViewResponse view = renderer.render(7L, resume);

        assertEquals(7L, view.resumeVersionId());
        List<String> lines = List.of(view.plainText().split("\n"));
        assertTrue(lines.contains("张三"), "页头缺姓名：" + lines);
        assertTrue(lines.contains("成都"), "页头缺城市：" + lines);
        assertTrue(lines.contains("SUMMARY"), "简介应有标准标题：" + lines);
        assertTrue(lines.contains("WORK EXPERIENCE"), "工作经历应有标准标题：" + lines);
        assertTrue(view.plainText().contains("高级工程师 | 某厂 | 2021-03 | 2024-06"), view.plainText());
        assertTrue(view.plainText().contains("- 主导订单链路重构"), "亮点要作为独立行：" + view.plainText());
        assertEquals(view.plainText().length(), view.charCount());
        assertFalse(view.plainText().contains("\n\n\n"),
                "解析文本不应出现连续空行（页头缺简介时曾出现三个换行）：" + view.plainText());
    }

    @Test
    @DisplayName("章节顺序遵循 layout.sectionOrder，未登记的键被忽略")
    void respectsSavedSectionOrder() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("skills", List.of(Map.of("name", "Java")));
        resume.put("work", List.of(Map.of("company", "A", "position", "P", "period", "2020 至今")));
        resume.put("layout", Map.of("sectionOrder", List.of("work", "skills", "不存在的章节")));

        ParserViewResponse view = renderer.render(1L, resume);

        List<String> codes = view.sections().stream().map(ParserViewResponse.SectionView::code).toList();
        assertEquals(List.of("basics", "work", "skills"), codes.subList(0, 3),
                "用户自定义顺序没生效或被未知键打乱：" + codes);
        assertTrue(view.plainText().indexOf("WORK EXPERIENCE") < view.plainText().indexOf("SKILLS"),
                "文本顺序必须与章节顺序一致：" + view.plainText());
    }

    @Test
    @DisplayName("未知顶层键告警：整块内容读不到是静默丢失，必须显式提示")
    void reportsUnknownTopLevelKeys() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("basics", Map.of("name", "张三"));
        resume.put("careerMaterials", List.of(Map.of("name", "不该出现在这里")));
        resume.put("template", Map.of("code", "classic"));
        resume.put("layout", Map.of("sectionOrder", List.of()));

        ParserViewResponse view = renderer.render(1L, resume);

        List<String> unknown = view.warnings().stream()
                .filter(w -> "UNKNOWN_SECTION".equals(w.code())).map(ParserViewResponse.Warning::section).toList();
        assertEquals(List.of("careerMaterials"), unknown, "只应告警真正未知的内容键：" + unknown);
        assertFalse(view.plainText().contains("template"), "排版配置不是简历内容，不该出现在解析文本里");
    }

    @Test
    @DisplayName("联系方式缺失与格式非法分别告警")
    void reportsContactProblems() {
        ParserViewResponse missing = renderer.render(1L, Map.of("basics", Map.of("name", "张三")));
        List<String> missingCodes = codes(missing);
        assertTrue(missingCodes.containsAll(List.of("MISSING_EMAIL", "MISSING_PHONE", "MISSING_LOCATION")),
                "缺联系方式应逐项告警：" + missingCodes);
        assertFalse(missingCodes.contains("MISSING_NAME"), "姓名存在就不该报缺失：" + missingCodes);

        ParserViewResponse invalid = renderer.render(1L, Map.of("basics",
                Map.of("name", "张三", "email", "not-an-email", "phone", "abc")));
        List<String> invalidCodes = codes(invalid);
        assertTrue(invalidCodes.contains("INVALID_EMAIL"), "非法邮箱应单独报：" + invalidCodes);
        assertTrue(invalidCodes.contains("INVALID_PHONE"), "非法电话应单独报：" + invalidCodes);
        assertFalse(invalid.contact().emailValid());
        assertTrue(invalid.contact().email(), "有邮箱字段就算「读到了」，格式问题由告警表达");
    }

    @Test
    @DisplayName("经历缺时间范围：报告受影响条目数（ATS 无法计算年限的隐性扣分）")
    void reportsItemsWithoutDates() {
        Map<String, Object> resume = resume(
                Map.of("name", "张三", "email", "a@b.co", "phone", "13800138000", "location", Map.of("city", "成都")),
                List.of(Map.of("company", "A", "position", "P"),
                        Map.of("company", "B", "position", "P", "startDate", "2020-01", "endDate", "2021-01")));

        ParserViewResponse view = renderer.render(1L, resume);

        ParserViewResponse.Warning warning = view.warnings().stream()
                .filter(w -> "ITEM_MISSING_DATES".equals(w.code())).findFirst().orElseThrow();
        assertEquals("work", warning.section());
        assertEquals(1, warning.count(), "两条经历里只有一条缺时间");
    }

    @Test
    @DisplayName("空集合不算「有内容」：不能给用户「章节已填」的错觉")
    void emptyCollectionsAreNotPresent() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("basics", Map.of("name", "张三"));
        resume.put("skills", List.of());
        resume.put("work", List.of());

        ParserViewResponse view = renderer.render(1L, resume);

        ParserViewResponse.SectionView skills = view.sections().stream()
                .filter(s -> "skills".equals(s.code())).findFirst().orElseThrow();
        assertFalse(skills.present());
        assertEquals(0, skills.itemCount());
        assertFalse(view.plainText().contains("SKILLS"), "空章节不该渲染标题：" + view.plainText());
        assertFalse(view.plainText().contains("\n\n\n"),
                "页头缺简介时不该出现连续空行：" + view.plainText());
        List<String> codes = codes(view);
        assertTrue(codes.contains("NO_SKILLS") && codes.contains("NO_EXPERIENCE"), "缺章节要告警：" + codes);
    }

    @Test
    @DisplayName("自定义章节用自己的标题渲染，条目沿用统一字段顺序")
    void rendersCustomSectionsWithOwnTitles() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("basics", Map.of("name", "张三"));
        resume.put("customSections", List.of(Map.of("title", "开源贡献",
                "entries", List.of(Map.of("name", "某项目", "role", "维护者")))));

        ParserViewResponse view = renderer.render(1L, resume);

        assertTrue(view.plainText().contains("开源贡献"), view.plainText());
        assertTrue(view.plainText().contains("某项目 | 维护者"), view.plainText());
        // 页头没有简介时后面紧跟章节标题：这条最容易出现三个连续换行（页头与章节各补一次空行）
        assertFalse(view.plainText().contains("\n\n\n"),
                "页头缺简介时不该出现连续空行：" + view.plainText());
        ParserViewResponse.SectionView custom = view.sections().stream()
                .filter(s -> "customSections".equals(s.code())).findFirst().orElseThrow();
        assertTrue(custom.present());
        assertEquals(1, custom.itemCount());
    }

    // ------------------------------------------------------------ 夹具

    private Map<String, Object> resume(Map<String, Object> basics, List<Map<String, Object>> work) {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("basics", basics);
        resume.put("work", work);
        return resume;
    }

    private List<String> codes(ParserViewResponse view) {
        List<String> codes = new ArrayList<>();
        for (ParserViewResponse.Warning warning : view.warnings()) codes.add(warning.code());
        return codes;
    }
}
