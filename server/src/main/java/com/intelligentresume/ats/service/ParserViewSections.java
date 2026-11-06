package com.intelligentresume.ats.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「解析器视角」的章节注册表：**唯一一份**章节键、渲染顺序、标题与字段顺序。
 *
 * <p>一句话定位：解析器"认识哪些章节、按什么顺序找、每章读哪几个字段"都登记在这里，
 * 被 {@code ResumeParserViewRenderer} 消费；改这里 = 改机器读简历的方式。
 *
 * <p>为什么单独成类而不是塞在渲染器里：这份注册表是**跨语言契约的一方**——
 * 它必须与前端 {@code web/src/resume/sectionRegistry.ts} 的 {@code SECTION_KEYS} 逐键相等，
 * 与后端 {@code ResumeSections.AI_CONTEXT_SECTIONS} 保持同一套键名。
 * 独立成类后，契约门禁可以只读这个文件做对拍，不用去解析渲染逻辑。
 *
 * <p>章节标题刻意用 **ATS 通用英文标准名**：解析器就是按这些名字找章节边界的，
 * 这里展示的是「机器在找什么」，不是界面上本地化后的标题。
 *
 * <p>要点（改动前必读）：
 * <ul>
 *   <li>{@link #SECTION_KEYS} 必须与前端 {@code SECTION_KEYS}、后端 {@code ResumeSections.AI_CONTEXT_SECTIONS}
 *       逐键相等；它是契约门禁的对拍对象，改键名要三处同时改。</li>
 *   <li>{@link #CONTENT_SPECS} 的顺序就是**默认章节顺序**，必须与前端 {@code DEFAULT_SECTION_ORDER} 一致；
 *       用户的 {@code layout.sectionOrder} 只改变渲染顺序，不会增删章节。</li>
 *   <li>{@code Spec.fields()} 的顺序决定输出文本的字段顺序 —— 顺序变了，同一份简历渲染出的解析文本就变了，
 *       任何依赖文本稳定的对比/快照都会失效。</li>
 *   <li>{@link #CUSTOM_ENTRY_FIELDS} 是"取最后一项的 fields"，即依赖 customSections 排在 CONTENT_SPECS 末尾；
 *       调整顺序时别把它挪走。</li>
 *   <li>{@link #DATED_SECTIONS} 决定哪些章节会报"缺时间"—— 加章节但漏加这里，是不会报错的静默漏检。</li>
 * </ul>
 */
public final class ParserViewSections {

    /**
     * 单个章节规范。
     *
     * @param code    章节键（前后端注册表同款）
     * @param heading 解析器寻找的标准标题（{@code basics} 为页头块，无标题）
     * @param fields  渲染时的字段顺序（决定输出文本的确定性）
     */
    public record Spec(String code, String heading, List<String> fields) {
    }

    /** {@code basics} 是页头信息块，不作为正文标题渲染。 */
    public static final Spec BASICS = new Spec("basics", "", List.of());

    /** 正文章节默认顺序 —— 必须与前端 {@code DEFAULT_SECTION_ORDER} 一致。 */
    public static final List<Spec> CONTENT_SPECS = List.of(
            new Spec("objective", "OBJECTIVE", List.of("targetRole", "targetIndustry", "summary")),
            new Spec("links", "LINKS", List.of("label", "url")),
            new Spec("work", "WORK EXPERIENCE",
                    List.of("position", "company", "startDate", "endDate", "period", "description")),
            new Spec("volunteering", "VOLUNTEERING",
                    List.of("role", "position", "organization", "name", "startDate", "endDate", "period", "description")),
            new Spec("skills", "SKILLS", List.of("name", "level", "keywords")),
            new Spec("projects", "PROJECTS",
                    List.of("name", "role", "startDate", "endDate", "period", "technologies", "description")),
            new Spec("education", "EDUCATION",
                    List.of("school", "institution", "degree", "major", "area", "startDate", "endDate", "period")),
            new Spec("courses", "COURSES", List.of("name", "provider", "date", "description")),
            new Spec("certificates", "CERTIFICATES", List.of("name", "issuer", "date")),
            new Spec("publications", "PUBLICATIONS",
                    List.of("title", "name", "publisher", "date", "description")),
            new Spec("awards", "AWARDS",
                    List.of("name", "title", "issuer", "organization", "date", "description")),
            new Spec("languages", "LANGUAGES", List.of("name", "language", "level", "fluency")),
            new Spec("customSections", "CUSTOM", List.of("name", "organization", "role", "description")));

    /** 自定义章节条目沿用的字段顺序（与默认 CUSTOM 规范同源）。 */
    public static final List<String> CUSTOM_ENTRY_FIELDS =
            CONTENT_SPECS.get(CONTENT_SPECS.size() - 1).fields();

    /** 章节键全集（含 basics）：契约门禁要求它等于前端 {@code SECTION_KEYS}。 */
    public static final Set<String> SECTION_KEYS = buildSectionKeys();

    /** 非内容型顶层键：排版与模板配置，不参与「未知章节」告警。 */
    public static final Set<String> STRUCTURAL_KEYS = Set.of("template", "layout");

    /** 需要时间范围的章节：缺时间 ATS 无法计算年限，是最常见的静默扣分项。 */
    public static final Set<String> DATED_SECTIONS = Set.of("work", "education", "projects", "volunteering");

    private ParserViewSections() {
    }

    private static Set<String> buildSectionKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(BASICS.code());
        for (Spec spec : CONTENT_SPECS) keys.add(spec.code());
        return Set.copyOf(keys);
    }
}
