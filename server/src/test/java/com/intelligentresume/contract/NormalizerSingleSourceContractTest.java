package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本清洗规则「单一事实来源」门禁（静态）。
 *
 * <p><b>背景</b>：{@code 小写 → 去标点 → 折叠空白 → trim} 这段逻辑曾在三个地方各写一遍字面量
 * ——{@code Normalizer#normalize}、{@code Normalizer#isDirectMatch}、
 * {@code ResumeKeywordExtractor#addText}。三份副本靠肉眼保持一致，任一处改字符集，
 * 简历侧 token 与 JD 关键词侧的清洗口径就会漂移：同一份文本在两侧被清洗成不同的 key，
 * 匹配结果系统性错位，且**没有任何报错**。
 *
 * <p>这是本项目反复踩到的「同一事实多份副本」缺陷形态，故收敛为单一入口
 * （{@code Normalizer#clean(String)}）后，必须有门禁钉住，否则下一次「顺手复制」就会复发。
 *
 * <p><b>为什么判据是「字面量出现次数」而不是「三次调用」</b>：调用点数量会随重构正常变化，
 * 但清洗规则的字符集只能有**一个**定义处。任何手抄副本都必然带来第二处字面量 →
 * 计数变 2 → 门禁变红。判据与它守护的缺陷形态一一对应。
 *
 * <p>特征串取 {@code \p{L}\p{N}} —— 这是该规则的指纹：三个副本都必须写这个字符集才能表达
 * 「保留字母数字」。折叠空白的 {@code \s+} 在项目里另有大量正当用途，不作为判据。
 */
class NormalizerSingleSourceContractTest {

    /**
     * 清洗规则的指纹 —— 注意取的是**源码文本形态**（Java 字面量里写 {@code \\p} 才是正则的 {@code \p}），
     * 即文件里实际出现的是双反斜杠。第一版误用单反斜杠，命中 0 处直接假红，
     * 说明「判据必须先在已知命中的样本上自证能命中」。
     *
     * <p>指纹取完整的保留集 {@code \p{L}\p{N}\s\-} 而不是只取 {@code \p{L}\p{N}}：
     * 后者会把 {@code MaterialSelectionContextBuilder} 的 {@code TOKEN_SPLIT = "[^\\p{L}\\p{N}]+"}
     * 一并命中，而那是**分词切割**（非字母数字即分隔符），与**清洗**（保留空白与连字符）
     * 是两条不同的事实，合并会改变分词行为。判据必须与它守护的事实同宽，不能更宽。
     */
    private static final String FINGERPRINT = "\\\\p{L}\\\\p{N}\\\\s\\\\-";

    /** 唯一允许的定义处。 */
    private static final String HOME_FILE = "Normalizer.java";

    @Test
    @DisplayName("清洗规则字面量在全仓 main 源码中只出现一处（不许手抄副本）")
    void cleaningRuleHasSingleSourceOfTruth() throws Exception {
        List<String> hits = new ArrayList<>();
        int scannedFiles = 0;

        for (Path file : javaSources()) {
            scannedFiles++;
            // 必须抹掉注释再判据：注释里提到正则（如说明「保留 \p{L}\p{N}」）会造成假红；
            // 反过来，注释里写着规则也会让「消费点是否存在」类判据假绿（见 SourceText 类注释）。
            String source = SourceText.read(file);
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(FINGERPRINT)) {
                    hits.add(file.getFileName() + ":" + (i + 1) + " → " + lines[i].trim());
                }
            }
        }

        assertTrue(scannedFiles >= 250,
                "扫描到的 main 源码过少（" + scannedFiles + " 个 .java），门禁可能未真正扫到源码树");

        assertEquals(1, hits.size(),
                "文本清洗规则的字符集必须只有一处定义（Normalizer.NON_WORD_CHARS），"
                        + "实际命中 " + hits.size() + " 处：\n  " + String.join("\n  ", hits)
                        + "\n手抄副本会导致简历侧与 JD 侧的清洗口径悄悄漂移（同一文本被清洗成不同 key，无任何报错）。"
                        + "改法：调用 Normalizer.clean(...)，不要复制字面量。");

        assertTrue(hits.get(0).startsWith(HOME_FILE),
                "清洗规则的定义处应在 " + HOME_FILE + "，实际为：" + hits.get(0));
    }

    @Test
    @DisplayName("跨类复用不回退：ResumeKeywordExtractor 必须走 Normalizer.clean")
    void resumeExtractorReusesSharedCleaner() throws Exception {
        Path extractor = repoFile("server/src/main/java/com/intelligentresume/scoring/service/ResumeKeywordExtractor.java");
        String source = SourceText.read(extractor);

        assertTrue(source.contains("Normalizer.clean("),
                "ResumeKeywordExtractor 必须调用 Normalizer.clean(token) 清洗 token；"
                        + "若改回内联 replaceAll，简历侧与 JD 侧会各自维护一套清洗规则。");
    }

    private List<Path> javaSources() throws Exception {
        try (Stream<Path> paths = Files.walk(repoFile("server/src/main/java"))) {
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
}
