package com.intelligentresume.export.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 Java PDF 渲染器（ADR-012 D4）。
 *
 * <p>断言的是「产出一份合法且含预期内容的 PDF」，而不是像素级排版 —— 排版与在线版（Chrome 打印）
 * 允许有差异，但**内容必须完整**（姓名、岗位、经历、技能都在），否则就是丢数据的假成功。
 */
class JavaResumePdfRendererTest {

    private static final List<String> TEMPLATES =
            List.of("classic", "modern", "minimal", "ats", "executive", "compact", "academic");

    @Test
    void rendersAValidPdfWithTheExpectedContent() throws Exception {
        assumeFontsAvailable();

        byte[] pdf = new JavaResumePdfRenderer().render("classic", sampleResume());

        assertTrue(pdf.length > 1000, "产出的 PDF 明显过小：" + pdf.length + " 字节");
        assertEquals("%PDF", new String(pdf, 0, 4), "PDF 文件头不正确");

        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() >= 1, "至少应有一页");
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("张三"), "PDF 文本应包含姓名，实际内容：" + preview(text));
            assertTrue(text.contains("后端工程师"), "PDF 文本应包含目标岗位，实际内容：" + preview(text));
            assertTrue(text.contains("示例科技"), "PDF 文本应包含工作经历公司名，实际内容：" + preview(text));
            assertTrue(text.contains("Java"), "PDF 文本应包含技能标签，实际内容：" + preview(text));
            assertTrue(text.contains("2024-01"), "PDF 文本应包含经历起止时间，实际内容：" + preview(text));
        }
    }

    @Test
    void everyTemplateRenders() throws Exception {
        assumeFontsAvailable();

        for (String template : TEMPLATES) {
            byte[] pdf = new JavaResumePdfRenderer().render(template, sampleResume());
            assertTrue(pdf.length > 1000, "模板 " + template + " 的产出过小：" + pdf.length + " 字节");
            try (PDDocument document = PDDocument.load(pdf)) {
                assertTrue(document.getNumberOfPages() >= 1, "模板 " + template + " 至少应有一页");
            }
        }
    }

    @Test
    void missingFontFailsLoudlyInsteadOfProducingGarbage() {
        // 字体缺失时必须明确报错（而不是静默产出中文豆腐块或空文件）
        boolean anyFont = FONT_CANDIDATES.stream().anyMatch(p -> Files.exists(Path.of(p)));
        Assumptions.assumeTrue(!anyFont, "本机存在系统字体，无法在此验证「缺字体」分支");
        try {
            new JavaResumePdfRenderer().render("classic", sampleResume());
        } catch (Exception ex) {
            assertNotNull(ex.getMessage());
            assertTrue(String.valueOf(ex.getMessage()).contains("字体")
                            || String.valueOf(ex.getCause()).contains("字体"),
                    "异常信息应点明是字体问题，实际：" + ex.getMessage());
            return;
        }
        throw new AssertionError("缺字体时应当抛异常");
    }

    private static final List<String> FONT_CANDIDATES = List.of(
            "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/georgia.ttf");

    private void assumeFontsAvailable() {
        boolean anyFont = FONT_CANDIDATES.stream().anyMatch(p -> Files.exists(Path.of(p)));
        Assumptions.assumeTrue(anyFont,
                "本机没有 Windows 自带字体（桌面端依赖它做中文排版）—— CI 的 ubuntu 环境跳过本用例");
    }

    private static Map<String, Object> sampleResume() {
        return Map.of(
                "basics", Map.of(
                        "name", "张三",
                        "title", "后端工程师",
                        "phone", "13800000000",
                        "email", "z@example.invalid",
                        "location", "成都",
                        "summary", "5 年后端服务经验。"),
                "work", List.of(Map.of(
                        "company", "示例科技",
                        "position", "高级后端工程师",
                        "startDate", "2024-01",
                        "endDate", "至今",
                        "highlights", List.of(Map.of("text", "负责订单服务开发与性能优化")))),
                "skills", List.of(Map.of(
                        "name", "Java",
                        "category", "后端",
                        "items", List.of("Java", "Spring Boot"),
                        "level", "熟练")),
                "education", List.of(Map.of(
                        "school", "某某大学",
                        "major", "软件工程",
                        "degree", "本科")));
    }

    private static String preview(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }
}
