package com.intelligentresume.contract;

import com.intelligentresume.ats.service.ParserViewSections;
import com.intelligentresume.resume.domain.ResumeSections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简历章节注册表三方门禁（B4）。
 *
 * <p>同一份「章节键」事实现在有三份副本，跨语言手工维护：
 * <ol>
 *   <li>前端 {@code web/src/resume/sectionRegistry.ts} 的 {@code SECTION_KEYS}（编辑器、渲染、ATS 跳转都用它）；</li>
 *   <li>后端 {@code ResumeSections.AI_CONTEXT_SECTIONS}（AI 输入白名单）；</li>
 *   <li>后端 {@code ParserViewSections.SECTION_KEYS}（解析器视角的章节与渲染顺序）。</li>
 * </ol>
 *
 * <p>为什么必须机器对拍：这类漂移**完全静默**——新增一个章节时，编辑器能编辑、能渲染，
 * 但解析器视角里它不存在（用户以为漏了内容），或者 AI 上下文里它被悄悄丢掉。
 * 反过来删一个章节时，解析器视角会永远报「未知章节」。三种表现都不是编译错误。
 *
 * <p>判据纪律：解析必须**命中锚点**，失配直接红（不用空集合冒充「相等」）；
 * 比较既比集合也比**顺序**——顺序决定用户看到的文本顺序，也决定 PDF 与解析器视角是否一致。
 */
class ParserViewSectionRegistryContractTest {

    private static final String REGISTRY = "web/src/resume/sectionRegistry.ts";

    @Test
    @DisplayName("解析器视角的章节键与顺序 = 前端 SECTION_KEYS")
    void parserSectionsMatchFrontendRegistry() throws Exception {
        List<String> frontend = frontendSectionKeys();
        assertFalse(frontend.isEmpty(), "未解析到前端 SECTION_KEYS（" + REGISTRY + "）—— 解析失效或符号改名");
        assertEquals(14, frontend.size(),
                "前端 SECTION_KEYS 条数变了（15 → 请同步 ParserViewSections 与本门禁的期望值）：" + frontend);

        List<String> backend = parserSectionKeys();
        assertEquals(frontend, backend,
                "章节键不一致或顺序不同：前端 " + frontend + " vs 解析器 " + backend);
    }

    @Test
    @DisplayName("AI 上下文白名单 = 解析器章节去掉 links")
    void aiContextSectionsAreParserSectionsMinusLinks() {
        List<String> expected = new ArrayList<>(parserSectionKeys());
        assertTrue(expected.remove("links"), "解析器章节里没有 links —— 与前端注册表已漂移");
        assertEquals(expected, ResumeSections.AI_CONTEXT_SECTIONS,
                "AI 上下文白名单与解析器章节不一致（links 之外的差异都是漂移）");
    }

    @Test
    @DisplayName("每个章节都有解析器可识别的标准标题")
    void everySectionHasHeading() {
        List<String> problems = new ArrayList<>();
        for (ParserViewSections.Spec spec : ParserViewSections.CONTENT_SPECS) {
            if (spec.heading() == null || spec.heading().isBlank()) {
                problems.add(spec.code() + " 缺标准标题");
            }
            if (spec.fields().isEmpty()) {
                problems.add(spec.code() + " 没有声明任何字段（渲染出来会是空行）");
            }
        }
        assertTrue(problems.isEmpty(), "章节规范不完整：" + problems);
    }

    // ------------------------------------------------------------ 解析

    /** 前端 {@code export const SECTION_KEYS = [...] as const} 的字面量顺序。 */
    private List<String> frontendSectionKeys() throws Exception {
        String source = Files.readString(repoFile(REGISTRY), StandardCharsets.UTF_8);
        Pattern block = Pattern.compile("SECTION_KEYS\\s*=\\s*\\[(.*?)]\\s*as const", Pattern.DOTALL);
        Matcher matcher = block.matcher(source);
        assertTrue(matcher.find(), "在 " + REGISTRY + " 里没找到 SECTION_KEYS = [...] as const 块 —— 锚点失配");
        List<String> keys = new ArrayList<>();
        Matcher literal = Pattern.compile("'([a-zA-Z]+)'").matcher(matcher.group(1));
        while (literal.find()) keys.add(literal.group(1));
        return keys;
    }

    /** 解析器顺序 = basics 之后按 {@code CONTENT_SPECS} 声明顺序。 */
    private List<String> parserSectionKeys() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(ParserViewSections.BASICS.code());
        for (ParserViewSections.Spec spec : ParserViewSections.CONTENT_SPECS) keys.add(spec.code());
        List<String> ordered = new ArrayList<>(keys);
        assertEquals(ParserViewSections.SECTION_KEYS.size(), ordered.size(),
                "SECTION_KEYS 与 CONTENT_SPECS 不一致（有重复或漏登记）");
        return ordered;
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
