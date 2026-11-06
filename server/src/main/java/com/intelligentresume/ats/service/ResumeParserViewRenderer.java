package com.intelligentresume.ats.service;

import com.intelligentresume.ats.dto.ParserViewResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 {@code resumeJson} 渲染成「解析器看到的线性文本 + 结构诊断」。
 *
 * <h2>① 解决什么业务问题</h2>
 * 用户写的简历在自己眼里是排版好的漂亮页面，但企业 ATS 机器读到的是一串线性文本。
 * "我的经历明明写了，为什么没被识别？"——本类把机器视角摊开给用户看：
 * 机器逐行读到了什么、哪些章节没认出来、哪些条目缺时间。
 *
 * <h2>② 在链路哪里</h2>
 * 被 {@code AtsService.parserView} 调用（对应 GET /api/ats/parser-view），
 * 输入是简历版本 JSON，产出 {@code ParserViewResponse} 给前端对照展示。
 * 章节定义来自 {@link ParserViewSections}（跨语言契约）。
 *
 * <h2>③ 改坏它用户会看到什么</h2>
 * 加入随机性/时间因素 → 同一份简历两次渲染结果不同，对照功能失效；
 * 章节标题改动 → 解析结果变化，前端高亮与快照对比全部错位；
 * 告警漏报 → 用户简历内容丢失却看不到任何提示。
 *
 * <p>纯函数、无依赖、无 AI：同一个版本永远得到同一份结果，这样用户才能把
 * 「解析器看到 X」和「我编辑的是 Y」逐行对照。一旦掺进模型判断，它就不再是
 * 「解析器看到什么」，而是「模型怎么想」——两件事必须分开，否则用户无法据此定位问题。
 *
 * <p>章节顺序 = {@code layout.sectionOrder} 里认识的键（保持用户顺序）+ 剩余已知章节按默认顺序补齐，
 * 与前端 {@code resolveSectionOrder()} 语义一致（未知键丢弃、缺失键追加）。
 * 章节键与标题的定义在 {@link ParserViewSections}（跨语言契约的一方）。
 *
 * <p>要点：
 * <ul>
 *   <li><b>纯函数</b>：不读库、不调 AI，同一份 {@code resumeJson} 永远得到同一份文本与告警。
 *       这是产品前提（用户要拿"解析器看到的"和"我编辑的"逐行对照），别在这里加任何随机/时间因素。</li>
 *   <li>输出是**线性纯文本** + 固定的英文标准章节标题；标题是给"解析器找边界"用的，
 *       不是界面本地化文案，改标题等于改解析结果。</li>
 *   <li>告警只返回码（{@code MISSING_EMAIL} / {@code UNKNOWN_SECTION} / {@code ITEM_MISSING_DATES} …），
 *       文案由前端映射，服务端不下发中文句子。</li>
 *   <li>未知顶层键必须告警：这次内容整块读不到，是最隐蔽的内容丢失；
 *       {@code template} / {@code layout} 属于排版配置，已列入豁免。</li>
 *   <li>{@code sections} 的第 0 项固定是 {@code basics}（页头块，无标题）；它的 {@code present}
 *       只看 name/email/phone 三者，location/title/links 不参与 —— 改判定会同时影响前端高亮。</li>
 * </ul>
 */
@Component
public class ResumeParserViewRenderer {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[+()\\-\\s\\d]{7,}$");

    /**
     * 渲染顺序：页头（basics + links + summary）→ 各正文章节 → 三类告警（缺联系方式 / 未知键 / 覆盖度）。
     *
     * <p>{@code charCount} 与 {@code lineCount} 都基于**去掉尾部空白之后**的文本统计；
     * 各 SectionView 的 {@code charCount} 则是"渲染前后 StringBuilder 长度差"，**含章节末尾补的空行**，
     * 因此各章节之和 ≥ 总字数（末尾空行被 {@code stripTrailing} 去掉），不要用它做精确占比。
     */
    public ParserViewResponse render(Long resumeVersionId, Map<String, Object> resumeJson) {
        Map<String, Object> json = resumeJson == null ? Map.of() : resumeJson;
        List<ParserViewResponse.Warning> warnings = new ArrayList<>();

        StringBuilder header = new StringBuilder();
        ParserViewResponse.ContactView contact = renderHeader(json, header, warnings);

        StringBuilder sb = new StringBuilder(header);
        List<ParserViewResponse.SectionView> sections = new ArrayList<>();
        sections.add(new ParserViewResponse.SectionView("basics", contact.name() || contact.email() || contact.phone(),
                1, header.length()));

        for (ParserViewSections.Spec spec : orderedSpecs(json)) {
            sections.add(renderSection(spec, spec.code(), json.get(spec.code()), sb, warnings));
        }

        reportMissingBasics(contact, warnings);
        reportUnknownKeys(json, warnings);
        reportCoverage(sections, warnings);

        String plainText = sb.toString().stripTrailing();
        return new ParserViewResponse(resumeVersionId, plainText.length(), lineCount(plainText),
                sections, contact, warnings, plainText);
    }

    // ---------------------------------------------------------------- 渲染

    /** {@code layout.sectionOrder} 优先（认识的键保持顺序），其余按默认顺序补齐。 */
    private List<ParserViewSections.Spec> orderedSpecs(Map<String, Object> json) {
        List<ParserViewSections.Spec> defaults = ParserViewSections.CONTENT_SPECS;
        Map<String, ParserViewSections.Spec> byCode = new LinkedHashMap<>();
        for (ParserViewSections.Spec spec : defaults) byCode.put(spec.code(), spec);

        List<ParserViewSections.Spec> ordered = new ArrayList<>();
        Object saved = mapValue(json.get("layout")).get("sectionOrder");
        if (saved instanceof List<?> list) {
            for (Object key : list) {
                ParserViewSections.Spec spec = key == null ? null : byCode.get(String.valueOf(key));
                if (spec != null && !ordered.contains(spec)) ordered.add(spec);
            }
        }
        for (ParserViewSections.Spec spec : defaults) if (!ordered.contains(spec)) ordered.add(spec);
        return ordered;
    }

    private ParserViewResponse.ContactView renderHeader(Map<String, Object> json, StringBuilder sb,
                                                        List<ParserViewResponse.Warning> warnings) {
        Map<String, Object> basics = mapValue(json.get("basics"));
        String name = firstText(basics, "name");
        String title = firstText(basics, "title", "label");
        String email = firstText(basics, "email");
        String phone = firstText(basics, "phone");
        String location = locationText(basics.get("location"));
        String summary = firstText(basics, "summary");

        appendLine(sb, name);
        appendLine(sb, title);
        appendLine(sb, location);
        appendLine(sb, email);
        appendLine(sb, phone);

        List<Map<String, Object>> links = maps(json.get("links"));
        for (Map<String, Object> link : links) appendLine(sb, joinText(link, List.of("label", "url"), ": "));
        if (summary != null) {
            // 简介属于页头块：只有存在时才插一个空行再打标准标题，避免出现连续空行
            appendBlank(sb);
            appendLine(sb, "SUMMARY");
            appendLine(sb, summary);
        }
        appendBlank(sb);

        boolean emailValid = email != null && EMAIL.matcher(email).matches();
        if (email != null && !emailValid) warnings.add(warning("INVALID_EMAIL", null, null));
        if (phone != null && !PHONE.matcher(phone).matches()) warnings.add(warning("INVALID_PHONE", null, null));
        return new ParserViewResponse.ContactView(
                name != null, email != null, emailValid, phone != null, location != null, links.size());
    }

    /**
     * 渲染单个章节并返回条目数。{@code customSections} 是两级结构（节 → entries），走单独分支；
     * 其余章节支持 List（多条，标题 + 逐条）/ Map（单条）/ 纯字符串三种形态，都为空则整段不输出。
     *
     * <p>缺时间告警只在"有条目且属于 DATED_SECTIONS"时统计，且计的是"缺 start/end 或 period"的条目数。
     */
    private ParserViewResponse.SectionView renderSection(ParserViewSections.Spec spec, String code, Object raw,
                                                         StringBuilder sb, List<ParserViewResponse.Warning> warnings) {
        int before = sb.length();
        int itemCount = "customSections".equals(code) ? renderCustomSections(raw, sb) : renderItems(spec, raw, sb);
        if (itemCount > 0 && ParserViewSections.DATED_SECTIONS.contains(code)) {
            int missing = countItemsWithoutDates(raw);
            if (missing > 0) warnings.add(warning("ITEM_MISSING_DATES", code, missing));
        }
        return new ParserViewResponse.SectionView(code, itemCount > 0, itemCount, sb.length() - before);
    }

    private int renderItems(ParserViewSections.Spec spec, Object raw, StringBuilder sb) {
        if (raw instanceof List<?>) {
            List<Map<String, Object>> items = maps(raw);
            if (items.isEmpty()) return 0;
            appendLine(sb, spec.heading());
            for (Map<String, Object> item : items) appendItem(spec.fields(), item, sb);
            appendBlank(sb);
            return items.size();
        }
        if (raw instanceof Map<?, ?>) {
            appendLine(sb, spec.heading());
            appendItem(spec.fields(), mapValue(raw), sb);
            appendBlank(sb);
            return 1;
        }
        if (raw != null && !String.valueOf(raw).isBlank()) {
            appendLine(sb, spec.heading());
            appendLine(sb, String.valueOf(raw));
            appendBlank(sb);
            return 1;
        }
        return 0;
    }

    private int renderCustomSections(Object raw, StringBuilder sb) {
        int count = 0;
        for (Map<String, Object> section : maps(raw)) {
            List<Map<String, Object>> entries = maps(section.get("entries"));
            if (entries.isEmpty()) continue;
            String title = firstText(section, "title");
            appendLine(sb, title == null ? "CUSTOM" : title);
            for (Map<String, Object> entry : entries) appendItem(ParserViewSections.CUSTOM_ENTRY_FIELDS, entry, sb);
            appendBlank(sb);
            count += entries.size();
        }
        return count;
    }

    private void appendItem(List<String> fields, Map<String, Object> item, StringBuilder sb) {
        appendLine(sb, joinText(item, fields, " | "));
        for (String highlight : highlights(item.get("highlights"))) appendLine(sb, "- " + highlight);
    }

    // -------------------------------------------------------------- 告警

    private void reportMissingBasics(ParserViewResponse.ContactView contact,
                                     List<ParserViewResponse.Warning> warnings) {
        if (!contact.name()) warnings.add(warning("MISSING_NAME", "basics", null));
        if (!contact.email()) warnings.add(warning("MISSING_EMAIL", "basics", null));
        if (!contact.phone()) warnings.add(warning("MISSING_PHONE", "basics", null));
        if (!contact.location()) warnings.add(warning("MISSING_LOCATION", "basics", null));
    }

    private void reportUnknownKeys(Map<String, Object> json, List<ParserViewResponse.Warning> warnings) {
        for (String key : json.keySet()) {
            if (ParserViewSections.SECTION_KEYS.contains(key)) continue;
            if (ParserViewSections.STRUCTURAL_KEYS.contains(key)) continue;
            // 未知顶层键整块都读不到 —— 最隐蔽的内容丢失，必须显式告警
            warnings.add(warning("UNKNOWN_SECTION", key, null));
        }
    }

    private void reportCoverage(List<ParserViewResponse.SectionView> sections,
                               List<ParserViewResponse.Warning> warnings) {
        Set<String> present = new LinkedHashSet<>();
        for (ParserViewResponse.SectionView section : sections) if (section.present()) present.add(section.code());
        if (!present.contains("work") && !present.contains("projects")) {
            warnings.add(warning("NO_EXPERIENCE", null, null));
        }
        if (!present.contains("skills")) warnings.add(warning("NO_SKILLS", "skills", null));
        if (!present.contains("education")) warnings.add(warning("NO_EDUCATION", "education", null));
    }

    // ------------------------------------------------------------ 工具方法

    private ParserViewResponse.Warning warning(String code, String section, Integer count) {
        return new ParserViewResponse.Warning(code, section, count);
    }

    private int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        return lines;
    }

    private int countItemsWithoutDates(Object raw) {
        int missing = 0;
        for (Map<String, Object> item : maps(raw)) {
            // 结构化区间（startDate/endDate）或源文自由区间（period）有其一即可，与生成提示词口径一致
            boolean hasStart = firstText(item, "startDate", "period") != null;
            boolean hasEnd = firstText(item, "endDate", "period") != null;
            if (!hasStart || !hasEnd) missing++;
        }
        return missing;
    }

    private void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) return;
        sb.append(line).append('\n');
    }

    private void appendBlank(StringBuilder sb) {
        if (!sb.isEmpty()) sb.append('\n');
    }

    private String joinText(Map<String, Object> item, List<String> fields, String separator) {
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            String value = firstText(item, field);
            if (value != null && !parts.contains(value)) parts.add(value);
        }
        return parts.isEmpty() ? null : String.join(separator, parts);
    }

    private String locationText(Object value) {
        if (value instanceof Map<?, ?>) return joinText(mapValue(value), List.of("city", "region", "country", "address"), ", ");
        return value == null ? null : String.valueOf(value);
    }

    private List<String> highlights(Object value) {
        List<String> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) return out;
        for (Object entry : list) {
            if (entry instanceof Map<?, ?>) {
                String text = firstText(mapValue(entry), "text", "name", "description");
                if (text != null) out.add(text);
            } else if (entry != null && !String.valueOf(entry).isBlank()) {
                out.add(String.valueOf(entry));
            }
        }
        return out;
    }

    private String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) continue;
            String text = value instanceof List<?> list
                    ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : String.valueOf(value);
            if (!text.isBlank()) return text.trim();
        }
        return null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> map.put(String.valueOf(key), item));
        return map;
    }

    private List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) return out;
        for (Object entry : list) if (entry instanceof Map<?, ?>) out.add(mapValue(entry));
        return out;
    }
}
