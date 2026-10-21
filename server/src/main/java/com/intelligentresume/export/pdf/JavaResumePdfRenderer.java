package com.intelligentresume.export.pdf;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面本地工作区的**纯 Java** 简历 PDF 渲染器（ADR-012 D4）。
 *
 * <p>为什么不用 Node + Puppeteer：那条链路要随安装包带 Chromium（约 292MB）与 Node 运行时，
 * 而桌面端的首要诉求是「一个安装包、离线可用、体积可控」。模板实况是 7 套样式共用同一套
 * 单栏结构（`.entry` 是 grid 1fr auto、`.chips` 是 flex wrap），足以用 PDFBox 直接绘制，
 * 不需要 HTML 排版引擎（Flying Saucer / OpenHTMLtoPDF 对 flex/grid 支持极差）。
 *
 * <p><b>字体不随包分发</b>：模板用 {@code Microsoft YaHei} / {@code Songti SC}，属商业字体不可再分发；
 * 但 Windows 10/11 自带，这里直接引用系统字体文件。找不到时**明确报错**而不是静默出豆腐块。
 *
 * <p><b>已知差异</b>：与在线版（Chrome 打印）的排版会有细微差别（字形宽度不同 → 换行位置可能不同）。
 * 两边共享的是同一份内容模型与设计 token（边距/字号/行距/颜色），差异只在绘制实现。
 */
@org.springframework.stereotype.Component
public class JavaResumePdfRenderer {

    /** 字体候选（Windows 系统字体路径，按顺序取第一个存在的）。 */
    private static final Map<String, List<String>> FONT_FILES = Map.of(
            "Microsoft YaHei", List.of("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/msyhl.ttc",
                    "C:/Windows/Fonts/Deng.ttf"),
            "Microsoft YaHei Bold", List.of("C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc",
                    "C:/Windows/Fonts/Dengb.ttf"),
            "SimSun", List.of("C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/simsunb.ttf",
                    "C:/Windows/Fonts/SimSunExtG.ttf"),
            "Georgia", List.of("C:/Windows/Fonts/georgia.ttf", "C:/Windows/Fonts/georgiab.ttf"),
            "Georgia Bold", List.of("C:/Windows/Fonts/georgiab.ttf", "C:/Windows/Fonts/georgia.ttf"),
            "Arial Bold", List.of("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/arial.ttf"),
            "Times New Roman Bold", List.of("C:/Windows/Fonts/timesbd.ttf", "C:/Windows/Fonts/times.ttf"),
            "Times New Roman", List.of("C:/Windows/Fonts/times.ttf", "C:/Windows/Fonts/timesbd.ttf"),
            "Arial", List.of("C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"));

    /** 7 套模板的样式参数（与 pdf-service/src/templates/classic.js 的 TEMPLATE_STYLES 对齐）。 */
    private record Style(String bodyFont,
                         String headingFont,
                         String bodyColor,
                         String headingColor,
                         String nameColor,
                         String roleColor,
                         String contactColor,
                         String metaColor,
                         String accentColor,
                         String background,
                         String chipBorder,
                         String chipText,
                         String chipFill,
                         boolean centeredHeader,
                         boolean chipRounded) {
    }

    private static final Map<String, Style> STYLES = buildStyles();

    /**
     * 渲染简历为 PDF 字节。
     *
     * @param templateCode 模板代号（classic / modern / minimal / ats / executive / compact / academic）
     * @param resumeJson   简历内容模型（结构化 JSON）
     */
    public byte[] render(String templateCode, Map<String, Object> resumeJson) throws IOException {
        Style style = STYLES.getOrDefault(templateCode, STYLES.get("classic"));
        Layout layout = Layout.of(resumeJson);

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Canvas canvas = new Canvas(document, style, layout);
            try {
                canvas.draw(resumeJson);
            } finally {
                canvas.close();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- 样式

    private static Map<String, Style> buildStyles() {
        Map<String, Style> map = new LinkedHashMap<>();
        map.put("classic", new Style("Georgia", "Microsoft YaHei", "#18212f", "#203b5c", "#14263e", "#40536c",
                "#62738a", "#53657b", "#ef9651", "#fbfcfe", "#d5dfe9", "#274769", null, false, true));
        map.put("modern", new Style("Georgia", "Microsoft YaHei", "#1d2f3f", "#173f5f", "#ffffff", "#ffffff",
                "#ffffff", "#53657b", "#4fa3c7", "#f7fafc", "#4fa3c7", "#173f5f", "#dcecf3", false, false));
        map.put("minimal", new Style("SimSun", "Microsoft YaHei", "#242424", "#222222", "#171717", "#333333",
                "#666666", "#666666", "#222222", "#ffffff", "#aaaaaa", "#333333", null, true, false));
        map.put("ats", new Style("Arial", "Microsoft YaHei", "#111111", "#111111", "#111111", "#111111",
                "#333333", "#333333", "#111111", "#ffffff", "#111111", "#111111", null, false, false));
        map.put("executive", new Style("Georgia", "Microsoft YaHei", "#182432", "#182432", "#182432", "#625c50",
                "#625c50", "#625c50", "#9b7b3f", "#fdfdfc", "#cfc4ae", "#493f2c", null, false, false));
        map.put("compact", new Style("Arial", "Microsoft YaHei", "#17212b", "#183f49", "#183f49", "#49646b",
                "#49646b", "#49646b", "#4f8d99", "#ffffff", "#bcd0d4", "#214f59", null, false, false));
        map.put("academic", new Style("Times New Roman", "Microsoft YaHei", "#202020", "#111111", "#111111", "#444444",
                "#444444", "#444444", "#555555", "#ffffff", "#222222", "#222222", null, false, false));
        return map;
    }

    /** 版面参数：与 classic.js 的 layoutStyles() 同源（同样的 0.75 换算）。 */
    private record Layout(float bodyFontSize,
                          float headingFontSize,
                          float nameFontSize,
                          float roleFontSize,
                          float lineHeight,
                          float sectionGap,
                          float entryGap,
                          float pagePadding) {
        static Layout of(Map<String, Object> resume) {
            Map<String, Object> l = asMap(resume.get("layout"));
            float body = (float) (bounded(asDouble(l.get("bodyFontSize")), 13, 11, 16) * 0.75);
            float heading = (float) (bounded(asDouble(l.get("headingFontSize")), 13, 11, 18) * 0.75);
            return new Layout(body, heading, heading * 24 / 9.75f, heading * 10.5f / 9.75f,
                    (float) bounded(asDouble(l.get("lineHeight")), 1.65, 1.3, 2),
                    (float) (bounded(asDouble(l.get("sectionSpacing")), 20, 10, 32) * 0.75),
                    (float) (bounded(asDouble(l.get("entrySpacing")), 12, 6, 22) * 0.75),
                    (float) (bounded(asDouble(l.get("pagePadding")), 58, 32, 80) * 0.75));
        }
    }

    // ---------------------------------------------------------------- 画布

    /** 逐页绘制；页底放不下时自动开新页。 */
    private static final class Canvas {

        private final PDDocument document;
        private final Style style;
        private final Layout layout;
        private final Map<String, PDType0Font> fonts = new LinkedHashMap<>();

        private PDPageContentStream stream;
        private float y;
        private final float margin;

        Canvas(PDDocument document, Style style, Layout layout) throws IOException {
            this.document = document;
            this.style = style;
            this.layout = layout;
            this.margin = (float) layout.pagePadding();
            newPage();
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - margin;
            if (style.background() != null) {
                fill(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight(), style.background());
            }
        }

        void draw(Map<String, Object> resume) throws IOException {
            Map<String, Object> basics = asMap(resume.get("basics"));
            Map<String, Object> objective = asMap(resume.get("objective"));

            // 页眉
            String name = text(basics, "name", "你的姓名");
            String role = firstNonEmpty(text(basics, "title", ""), text(basics, "position", ""),
                    text(basics, "label", ""), "目标岗位");
            String location = asString(basics.get("location"));
            String contact = String.join(" · ", List.of(
                    text(basics, "phone", ""), text(basics, "email", ""), location).stream()
                    .filter(s -> !s.isBlank()).toList());

            float headerTop = y;
            if (style.centeredHeader()) {
                y -= layout.nameFontSize();
                drawCentered(name, layout.nameFontSize(), style.nameColor(), bold());
            } else {
                y -= layout.nameFontSize();
                drawLeft(name, layout.nameFontSize(), style.nameColor(), bold());
                // 姓名与岗位之间必须显式留白：在「先减 y 再绘制、基线 = y - 字号」的约定下，
                // 两行基线之差只由字号决定（2*roleSize + 3 - nameSize），当两者恰好相等时，
                // 后画的岗位文字会**盖住姓名**（实测提取不到「张三」）。这里额外留 6pt。
                y -= 6;
            }
            y -= layout.roleFontSize() + 3;
            drawLeft(role, layout.roleFontSize(), style.roleColor(), bold());
            y -= 11;
            drawLeft(contact, 9, style.contactColor(), body());
            y -= 8;
            drawHeaderRule(headerTop);

            // 概要
            String summary = text(basics, "summary", "");
            if (!summary.isBlank()) {
                y -= layout.sectionGap();
                drawSectionTitle("个人概要");
                y = drawWrapped(summary, layout.bodyFontSize(), style.bodyColor(), body());
            }

            String targetSummary = text(objective, "summary", "");
            if (!targetSummary.isBlank()) {
                String meta = String.join(" · ", List.of(text(objective, "targetRole", ""),
                        text(objective, "targetIndustry", ""), text(objective, "location", "")).stream()
                        .filter(s -> !s.isBlank()).toList());
                if (!meta.isBlank()) {
                    y = drawWrapped(meta, layout.bodyFontSize() - 1, style.metaColor(), body());
                }
                y = drawWrapped(targetSummary, layout.bodyFontSize(), style.bodyColor(), body());
            }

            drawEntries(resume, "links", "个人链接", item -> List.of(
                    new Line(firstNonEmpty(text(item, "label", ""), text(item, "name", "")), true,
                            text(item, "url", ""), null)));
            drawEntries(resume, "work", "工作经历", item -> List.of(
                    new Line(firstNonEmpty(text(item, "company", ""), text(item, "name", ""), "公司名称"), true,
                            text(item, "position", ""), dateRange(item))));
            drawEntries(resume, "skills", "专业技能", null);
            drawEntries(resume, "projects", "项目经历", item -> List.of(
                    new Line(firstNonEmpty(text(item, "name", ""), "项目名称"), true,
                            text(item, "role", ""), text(item, "position", ""))));
            drawEntries(resume, "education", "教育经历", item -> List.of(
                    new Line(firstNonEmpty(text(item, "school", ""), text(item, "name", "")), true,
                            String.join(" · ", List.of(text(item, "degree", ""),
                                    firstNonEmpty(text(item, "major", ""), text(item, "area", ""))).stream()
                                    .filter(s -> !s.isBlank()).toList()), null)));
            drawEntries(resume, "certificates", "专业证书", item -> List.of(
                    new Line(firstNonEmpty(text(item, "name", ""), "证书名称"), true,
                            text(item, "issuer", ""), text(item, "date", ""))));
            drawEntries(resume, "languages", "语言能力", null);
        }

        /** 章节：标题 + 装饰线。 */
        private void drawSectionTitle(String title) throws IOException {
            y -= layout.sectionGap();
            y -= layout.headingFontSize();
            drawLeft(title, layout.headingFontSize(), style.headingColor(), bold());
            y -= 4;
            fill(margin, y, margin + 20, y + 1, style.accentColor());
            y -= 8;
        }

        private record Line(String title, boolean bold, String subtitle, String date) {
        }

        /** 经历日期：结构化起止优先，回退 period（与 pdf-service classic.js 的 dates() 同语义）。 */
        private static String dateRange(Map<String, Object> item) {
            String start = text(item, "startDate", "");
            String end = text(item, "endDate", "");
            if (!start.isBlank() || !end.isBlank()) {
                return (start + " — " + end).trim();
            }
            return text(item, "period", "");
        }

        @FunctionalInterface
        private interface EntryRenderer {
            List<Line> lines(Map<String, Object> item);
        }

        private void drawEntries(Map<String, Object> resume, String key, String sectionTitle, EntryRenderer renderer)
                throws IOException {
            List<Object> items = asList(resume.get(key));
            if (items.isEmpty()) {
                return;
            }
            if ("skills".equals(key) || "languages".equals(key)) {
                drawSectionTitle(sectionTitle);
                y = drawChips(items);
                y -= layout.entryGap();
                return;
            }
            drawSectionTitle(sectionTitle);
            for (Object item : items) {
                List<Line> lines = renderer == null ? defaultLines(item) : renderer.lines(asMap(item));
                for (Line line : lines) {
                    if (line.title().isBlank() && line.subtitle().isBlank()) {
                        continue;
                    }
                    y -= layout.bodyFontSize() + 4;
                    drawLeft(line.title(), layout.bodyFontSize() + 0.75f, style.bodyColor(),
                            line.bold() ? bold() : body());
                    if (line.date() != null && !line.date().isBlank()) {
                        drawRight(line.date(), layout.bodyFontSize() - 0.5f, style.metaColor(), body());
                    }
                    if (!line.subtitle().isBlank()) {
                        y -= layout.bodyFontSize() + 2;
                        drawLeft(line.subtitle(), layout.bodyFontSize() - 0.5f, style.metaColor(), body());
                    }
                    String description = text(asMap(item), "description", "");
                    if (!description.isBlank()) {
                        y -= layout.bodyFontSize() + 3;
                        y = drawWrapped(description, layout.bodyFontSize(), style.bodyColor(), body());
                    }
                    for (Object point : asList(asMap(item).get("highlights"))) {
                        String pointText = text(asMap(point), "text", text(asMap(point), "value", ""));
                        if (pointText.isBlank()) {
                            pointText = String.valueOf(point);
                        }
                        if (pointText.isBlank()) {
                            continue;
                        }
                        y -= layout.bodyFontSize() + 2;
                        y = drawWrapped("• " + pointText, layout.bodyFontSize(), style.bodyColor(), body(), 10);
                    }
                }
                y -= layout.entryGap();
            }
        }

        private List<Line> defaultLines(Object item) {
            Map<String, Object> map = asMap(item);
            return List.of(new Line(text(map, "name", ""), true, text(map, "role", ""), null));
        }

        /** 技能/语言标签：横向排布，满行换行。 */
        private float drawChips(List<Object> items) throws IOException {
            List<String> labels = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof String s) {
                    if (!s.isBlank()) {
                        labels.add(s.trim());
                    }
                    continue;
                }
                Map<String, Object> map = asMap(item);
                for (String key : List.of("name", "keyword", "items", "keywords")) {
                    Object value = map.get(key);
                    if (value instanceof String s && !s.isBlank()) {
                        labels.add(s.trim());
                    } else if (value instanceof List<?> list) {
                        for (Object element : list) {
                            if (element != null && !String.valueOf(element).isBlank()) {
                                labels.add(String.valueOf(element).trim());
                            }
                        }
                    }
                }
            }
            float chipSize = layout.bodyFontSize() - 0.5f;
            float x = margin;
            for (String label : labels.stream().distinct().toList()) {
                PDFont font = body();
                float textWidth = width(font, label, chipSize);
                float chipWidth = textWidth + 10;
                float chipHeight = chipSize + 5;
                if (x + chipWidth > PDRectangle.A4.getWidth() - margin) {
                    x = margin;
                    y -= chipHeight + 3;
                }
                if (style.chipFill() != null) {
                    fill(x, y - chipHeight + 2, chipWidth, chipHeight, style.chipFill());
                }
                if (style.chipBorder() != null) {
                    strokeRect(x, y - chipHeight + 2, chipWidth, chipHeight, style.chipBorder());
                }
                showText(x + 5, y - chipHeight + 6, label, chipSize, style.chipText(), font);
                x += chipWidth + 4;
            }
            return y;
        }

        // ------------------------------------------------------------ 绘制原语

        private float drawWrapped(String content, float size, String color, PDFont font) throws IOException {
            return drawWrapped(content, size, color, font, 0);
        }

        private float drawWrapped(String content, float size, String color, PDFont font, float indent)
                throws IOException {
            float lineHeight = size * (float) layout.lineHeight();
            float available = PDRectangle.A4.getWidth() - margin * 2 - indent;
            for (String paragraph : content.split("\n")) {
                String line = "";
                for (String ch : paragraph.split("", -1)) {
                    String candidate = line + ch;
                    if (width(font, candidate, size) > available && !line.isEmpty()) {
                        y -= lineHeight;
                        ensureRoom(lineHeight);
                        drawLeft(line, size, color, font, indent);
                        line = ch;
                    } else {
                        line = candidate;
                    }
                }
                y -= lineHeight;
                ensureRoom(lineHeight);
                drawLeft(line, size, color, font, indent);
            }
            return y;
        }

        private void drawLeft(String content, float size, String color, PDFont font) throws IOException {
            drawLeft(content, size, color, font, 0);
        }

        private void drawLeft(String content, float size, String color, PDFont font, float indent)
                throws IOException {
            showText(margin + indent, y, content, size, color, font);
        }

        private void drawCentered(String content, float size, String color, PDFont font) throws IOException {
            float textWidth = width(font, content, size);
            showText((PDRectangle.A4.getWidth() - textWidth) / 2, y, content, size, color, font);
        }

        private void drawRight(String content, float size, String color, PDFont font) throws IOException {
            float textWidth = width(font, content, size);
            showText(PDRectangle.A4.getWidth() - margin - textWidth, y, content, size, color, font);
        }

        private void showText(float x, float yTop, String content, float size, String color, PDFont font)
                throws IOException {
            if (content == null || content.isEmpty()) {
                return;
            }
            PDFont cjk = cjkFor(font);
            float cursor = x;
            for (String run : splitRuns(content)) {
                PDFont runFont = isAsciiRun(run) ? font : cjk;
                boolean began = false;
                try {
                    stream.beginText();
                    began = true;
                    stream.setFont(runFont, size);
                    stream.setNonStrokingColor(hex(color));
                    stream.newLineAtOffset(cursor, yTop - size);
                    stream.showText(sanitize(run));
                } catch (IOException | IllegalArgumentException ex) {
                    // 字体缺字：跳过该片段而不是让整份 PDF 失败
                } finally {
                    // 必须闭合文本块：endText 放在 try 里时，一旦 showText 抛异常就会漏掉，
                    // 文本块保持打开 → 后续 addRect 抛「addRect is not allowed within a text block」，
                    // 整份 PDF 报废。
                    if (began) {
                        try {
                            stream.endText();
                        } catch (IOException ignored) {
                            // 文本块已损坏，继续绘制即可
                        }
                    }
                }
                cursor += widthOf(run, size, runFont);
            }
        }

        /** 按「连续 ASCII / 连续非 ASCII」切分文本。 */
        private static List<String> splitRuns(String content) {
            List<String> runs = new ArrayList<>();
            if (content == null || content.isEmpty()) {
                return runs;
            }
            StringBuilder current = new StringBuilder();
            boolean currentAscii = isAsciiRun(content);
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                boolean ascii = ch < 128;
                if (ascii != currentAscii) {
                    runs.add(current.toString());
                    current.setLength(0);
                    currentAscii = ascii;
                }
                current.append(ch);
            }
            runs.add(current.toString());
            return runs;
        }

        private static boolean isAsciiRun(String run) {
            for (int i = 0; i < run.length(); i++) {
                if (run.charAt(i) >= 128) {
                    return false;
                }
            }
            return true;
        }

        private void drawHeaderRule(float headerTop) throws IOException {
            float ruleY = headerTop - 6;
            fill(margin, ruleY, PDRectangle.A4.getWidth() - margin, ruleY + 1, "#283c59");
            y = ruleY - 6;
        }

        private void fill(float x, float yBottom, float width, float height, String color) throws IOException {
            stream.setNonStrokingColor(hex(color));
            stream.addRect(x, yBottom, width, height);
            stream.fill();
        }

        private void strokeRect(float x, float yBottom, float width, float height, String color)
                throws IOException {
            stream.setStrokingColor(hex(color));
            stream.setLineWidth(0.7f);
            stream.addRect(x, yBottom, width, height);
            stream.stroke();
        }

        private void ensureRoom(float needed) throws IOException {
            if (y - needed < margin) {
                newPage();
            }
        }

        private float width(PDFont font, String content, float size) throws IOException {
            PDFont cjk = cjkFor(font);
            float total = 0;
            for (String run : splitRuns(content)) {
                total += widthOf(run, size, isAsciiRun(run) ? font : cjk);
            }
            return total;
        }

        private float widthOf(String text, float size, PDFont font) throws IOException {
            return font.getStringWidth(sanitize(text)) / 1000f * size;
        }

        /** PDFBox 的标准 14 种字体无法编码中文，WinAnsi 之外字符一律替换。 */
        private String sanitize(String content) {
            if (content == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder(content.length());
            for (char ch : content.toCharArray()) {
                if (ch == '\n' || ch == '\r' || ch == '\t') {
                    builder.append(' ');
                } else if (ch < 256) {
                    builder.append(ch);
                } else {
                    // 非 Latin 字符交给嵌入的 TrueType 字体（可编码），此处仅剔除控制字符
                    builder.append(ch);
                }
            }
            return builder.toString();
        }

        /** 字体 → 其中文回退字体族（原模板是字体栈：西文用 Georgia，中文回退 "Songti SC"）。 */
        private final Map<PDFont, String> cjkFamily = new java.util.IdentityHashMap<>();

        private PDFont body() throws IOException {
            PDFont result = font(style.bodyFont());
            cjkFamily.putIfAbsent(result,
                    isCjkFamily(style.bodyFont()) ? style.bodyFont() : "Microsoft YaHei");
            return result;
        }

        private PDFont bold() throws IOException {
            String family = boldFamilyFor(style.headingFont());
            PDFont result = font(family);
            cjkFamily.putIfAbsent(result,
                    isCjkFamily(family) ? family : "Microsoft YaHei Bold");
            return result;
        }

        private static boolean isCjkFamily(String family) {
            return "SimSun".equals(family) || "Microsoft YaHei".equals(family)
                    || "Microsoft YaHei Bold".equals(family);
        }

        private static String boldFamilyFor(String headingFont) {
            return switch (headingFont) {
                case "Georgia" -> "Georgia Bold";
                case "Arial" -> "Arial Bold";
                case "Times New Roman" -> "Times New Roman Bold";
                case "Microsoft YaHei" -> "Microsoft YaHei Bold";
                default -> headingFont;
            };
        }

        /** 取某西文字体对应的中文字体（缺字时用）。 */
        private PDFont cjkFor(PDFont latin) throws IOException {
            return font(cjkFamily.getOrDefault(latin, "Microsoft YaHei"));
        }

        private PDFont font(String family) throws IOException {
            return fonts.computeIfAbsent(family, key -> {
                try {
                    return loadFont(document, key);
                } catch (IOException ex) {
                    throw new IllegalStateException(
                            "无法加载字体 " + key + "（桌面端需要 Windows 自带的中英文字体）：" + ex.getMessage(), ex);
                }
            });
        }

        private static PDType0Font loadFont(PDDocument document, String family) throws IOException {
            List<String> candidates = FONT_FILES.getOrDefault(family, List.of());
            for (String candidate : candidates) {
                Path path = Path.of(candidate);
                if (!Files.exists(path)) {
                    continue;
                }
                if (candidate.endsWith(".ttc")) {
                    // ⚠ 这里**不能**用 try-with-resources 关闭 collection：
                    // PDFBox 2.0 在嵌入字体子集时会回读字体数据，而 TrueTypeFont 仍引用着
                    // collection 的数据流 —— 集合一关，渲染时报
                    // 「NullPointerException: ... this.raf is null」。让 collection 随 document 一起回收。
                    TrueTypeCollection collection = new TrueTypeCollection(path.toFile());
                    TrueTypeFont font = collection.getFontByName(family);
                    if (font == null) {
                        // .ttc 里同一个文件可能登记了多个字体名（如 "Microsoft YaHei" 与
                        // "Microsoft YaHei UI"），按名字取会落空 —— 退而取集合里的第一个字体。
                        final TrueTypeFont[] picked = new TrueTypeFont[1];
                        collection.processAllFonts(new TrueTypeCollection.TrueTypeFontProcessor() {
                            @Override
                            public void process(TrueTypeFont candidate) {
                                if (picked[0] == null) {
                                    picked[0] = candidate;
                                }
                            }
                        });
                        font = picked[0];
                    }
                    if (font != null) {
                        return PDType0Font.load(document, font, true);
                    }
                } else {
                    return PDType0Font.load(document, path.toFile());
                }
            }
            throw new IOException("在系统中找不到字体文件：" + String.join(" / ", candidates));
        }
    }

    // ---------------------------------------------------------------- 工具

    private static java.awt.Color hex(String hex) {
        if (hex == null) {
            return java.awt.Color.BLACK;
        }
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (value.length() == 6) {
            return new java.awt.Color(Integer.parseInt(value, 16));
        }
        return java.awt.Color.BLACK;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private static double bounded(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.min(max, Math.max(min, value));
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
