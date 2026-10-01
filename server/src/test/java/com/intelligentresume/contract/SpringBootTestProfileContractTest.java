package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试环境自洽门禁（静态）：每个 {@code @SpringBootTest} 都必须声明 {@code @ActiveProfiles}。
 *
 * <p>背景（第六十五批实测教训）：新写的 {@code RetentionPurgeRepositorySchemaTest} 用了
 * {@code @SpringBootTest} 但**漏了 {@code @ActiveProfiles("test")}** ⇒ 它连的是
 * {@code application.yml} 的默认数据源（MySQL）。后果：
 * <ul>
 *   <li><b>本机</b>恰好装有 MySQL ⇒ 该类"全绿"，全量 919 也全绿（**假阳性**）；</li>
 *   <li><b>CI</b> 没有 MySQL ⇒ {@code Communications link failure}，整个 Server tests 作业失败。</li>
 * </ul>
 * 也就是说：一个测试的通过与否取决于**运行者机器上有没有那个数据库**，而它在本地永远发现不了。
 * 本门禁把这条不成文约定变成可检查的断言 —— 需要默认 profile 的测试必须显式说明（当前没有），
 * 不能靠"恰好能跑"。
 */
class SpringBootTestProfileContractTest {

    // 只认**行首**注解：裸 substring 匹配会被注释/字符串里的同名文本骗过。
    // 这不是理论担忧 —— 本门禁首版用 `source.contains("@ActiveProfiles")`，
    // 而被检查文件的 javadoc 里正好写了这句话，于是"去掉注解"后门禁**依然通过**（假绿），
    // 是红判定把它抓出来的。故：先剥注释，再要求注解出现在行首。
    private static final Pattern SPRING_BOOT_TEST = Pattern.compile("(?m)^[ \\t]*@SpringBootTest\\b");
    private static final Pattern ACTIVE_PROFILES = Pattern.compile("(?m)^[ \\t]*@ActiveProfiles\\b");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)//[^\\n]*");

    @Test
    @DisplayName("每个 @SpringBootTest 都声明了 @ActiveProfiles（否则会连默认 MySQL 数据源，只在有本地库时才绿）")
    void everySpringBootTestPinsAProfile() throws Exception {
        Path testRoot = repoFile("server/src/test/java");

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(testRoot)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }

        int springBootTests = 0;
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles) {
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!SPRING_BOOT_TEST.matcher(code).find()) {
                continue;
            }
            springBootTests++;
            if (!ACTIVE_PROFILES.matcher(code).find()) {
                offenders.add("  " + testRoot.relativize(file));
            }
        }

        // 规模自检：用例数过少说明扫描路径失效，此时本门禁会永远通过（比失败更危险）
        assertTrue(springBootTests >= 30,
                "扫描到 " + springBootTests + " 个 @SpringBootTest，明显偏少 —— 扫描路径可能已失效，"
                        + "此时本门禁会永远通过");

        assertTrue(offenders.isEmpty(),
                "以下 @SpringBootTest 未声明 @ActiveProfiles：它们会使用 application.yml 的默认数据源"
                        + "（MySQL），于是**本机有 MySQL 就绿、CI 直接 Communications link failure**；"
                        + "请补 @ActiveProfiles(\"test\")（或显式说明为何需要默认 profile）：\n"
                        + String.join("\n", offenders));
    }

    /** 剥掉块注释与行注释：注解是否真的存在，不能被注释里的同名文本污染。 */
    private String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
