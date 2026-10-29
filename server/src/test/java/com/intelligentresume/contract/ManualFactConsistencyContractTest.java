package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档派生指标与仓库实际结构的**一致性门禁**。
 *
 * <p>背景：README 曾同时写着「Flyway V1–V37」（实际 38 个迁移）、「后端 1019 个测试」（实际 1106）、
 * 「17 项部署探针」（实际 24 项）、「最新发布说明 v0.4.1」（实际 v0.4.4）。这四个数字有一个共同点 ——
 * **没有人负责更新它们**，于是每次发版都多腐烂一点，而没有任何测试会红。
 *
 * <p>本门禁只钉**能从仓库自身算出真值**的指标：
 * <ul>
 *   <li>迁移版本区间与「N 条迁移」—— 真值就是 {@code db/migration} 的文件数；</li>
 *   <li>探针项数 —— 真值就是 {@code scripts/probe-deployment.sh} 的 {@code EXPECTED_ITEMS_SSH}；</li>
 *   <li>最新发布说明 —— 真值就是 {@code docs/RELEASES/} 的文件名；</li>
 *   <li>文档索引覆盖度 —— 真值就是 `plans/`、`reviews/`、`decisions/ADR-*.md` 的文件清单。</li>
 * </ul>
 *
 * <p><b>测试条数刻意不允许写成精确值</b>：Surefire 报告不入库，没有可静态对齐的锚，硬写一个数字
 * 必然腐烂（2026-10-29 实测 `docs/CI.md` 仍写「310 个测试」而实际 1111 个）。所以只允许
 * 「1100+」这类下限形态或指向 `docs/RELEASES/`。原则是：**算不出真值的指标，就不要写成精确值。**
 *
 * <p>历史快照（`docs/plans`、`docs/reviews`、`docs/RELEASES`）不参与：它们记录的是**当时**的事实，
 * 改成今天的数字反而制造第二类失真。同一文档里「此前曾写 XX」这类回顾句也豁免。
 *
 * <p>每条断言都要求「锚点必须命中」——文档被改写导致正则失配时**报红而不是跳过**。
 * 一个匹配 0 处却静默通过的检查，是本仓库已经踩过的假绿形态。
 */
class ManualFactConsistencyContractTest {

    private static final String MIGRATION_DIR = "server/src/main/resources/db/migration";
    private static final String RELEASE_DIR = "docs/RELEASES";
    private static final String PROBE_SCRIPT = "scripts/probe-deployment.sh";

    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern MIGRATION_RANGE = Pattern.compile("V1[–\\-~]V(\\d+)");
    private static final Pattern RELEASE_FILE = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)\\.md");
    private static final Pattern RELEASE_LINK = Pattern.compile("docs/RELEASES/v(\\d+\\.\\d+\\.\\d+)\\.md");
    private static final Pattern VERSION_BADGE = Pattern.compile("badge/version-(\\d+\\.\\d+\\.\\d+)-");

    /** 现行手册：声明的是「现在的事实」，与仓库实际不符即红。 */
    private static final List<String> FACT_DOCS = List.of(
            "README.md", "README.en.md", "docs/CI.md", "docs/DEPLOYMENT_DIRECT.md",
            "docs/08-部署与运维说明书.md", "PROJECT_CONTEXT.md");

    /** 「N 项探针」「N 项部署后探针」「部署后 N 项探针」三种写法。状态机探针（另一脚本）不匹配。 */
    private static final Pattern PROBE_ITEMS =
            Pattern.compile("(\\d+)\\s*项(?:部署后)?探针|部署后\\s*(\\d+)\\s*项探针");

    private static final Pattern MIGRATION_COUNT = Pattern.compile("(\\d+)\\s*(?:条|个)迁移");

    /**
     * 「N 个测试」只允许下限形态（{@code 1100+ 个测试}）：数字后面紧跟 {@code +} 时不匹配本正则。
     * 精确条数无法静态对齐，写死必然腐烂。
     */
    private static final Pattern EXACT_TEST_COUNT = Pattern.compile("(\\d+)\\s*个测试");

    /** 回顾句豁免：「此前 README 写 17 项探针」是在讲过去，不是在声明现在。 */
    private static final Pattern HISTORICAL = Pattern.compile("此前|历史上|曾经|过去");

    /**
     * markdown 的 HTML 注释。渲染后不可见，因此**不能**当作「文档声明」参与判据 ——
     * 否则在 README 顶部写一行 {@code <!-- 反例：V1–V37 -->} 就能让门禁假红，
     * 而在注释里写「已索引」又能让索引检查假绿。
     */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /** 声明了「V1–Vn」区间的文档。每份都必须至少命中一次。 */
    private static final List<String> RANGE_DOCS = List.of("README.md", "README.en.md");

    /** 必须指向最新发布说明的文档。 */
    private static final List<String> RELEASE_LINK_DOCS = List.of("README.md", "README.en.md");

    @Test
    @DisplayName("迁移文件数 == 最高版本号，且 V1..Vn 连续无缺口")
    void migrationsAreContiguous() throws Exception {
        List<Integer> versions = migrationVersions();

        assertTrue(versions.size() >= 30,
                "只解析出 " + versions.size() + " 个迁移文件 —— 目录路径变了或文件名格式变了（不得当成通过）");

        int highest = versions.get(versions.size() - 1);
        assertEquals(versions.size(), highest,
                "迁移文件数 " + versions.size() + " 与最高版本号 V" + highest + " 不相等 —— "
                        + "说明编号存在缺口或重号（V1..Vn 必须连续递增）");

        for (int i = 0; i < versions.size(); i++) {
            assertEquals(i + 1, versions.get(i).intValue(),
                    "按序第 " + (i + 1) + " 个迁移应为 V" + (i + 1) + "，实际是 V" + versions.get(i));
        }
    }

    @Test
    @DisplayName("文档里的 V1–Vn 区间等于仓库实际最高版本号")
    void documentedMigrationRangeMatchesRepository() throws Exception {
        List<Integer> versions = migrationVersions();
        int highest = versions.get(versions.size() - 1);
        List<String> problems = new ArrayList<>();

        for (String doc : RANGE_DOCS) {
            Matcher matcher = MIGRATION_RANGE.matcher(read(doc));
            int hits = 0;
            while (matcher.find()) {
                hits++;
                int stated = Integer.parseInt(matcher.group(1));
                if (stated != highest) {
                    problems.add("  " + doc + " 写 V1–V" + stated + "，实际最高是 V" + highest);
                }
            }
            assertTrue(hits > 0, doc + " 里找不到「V1–V<n>」形式的迁移区间表述 —— "
                    + "锚点失效（不得当成通过；若确实不再声明该区间，请同步删掉本门禁的这份文档）");
        }

        assertTrue(problems.isEmpty(),
                "迁移版本区间与仓库不一致（真值 = db/migration 的文件数）：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("README 的版本徽章与发布说明链接都指向最新一版")
    void readmePointsAtLatestRelease() throws Exception {
        String latest = latestReleaseVersion();
        List<String> problems = new ArrayList<>();

        for (String doc : RELEASE_LINK_DOCS) {
            Matcher matcher = RELEASE_LINK.matcher(read(doc));
            int hits = 0;
            while (matcher.find()) {
                hits++;
                if (!matcher.group(1).equals(latest)) {
                    problems.add("  " + doc + " 链接到 v" + matcher.group(1) + " 的发布说明，最新是 v" + latest);
                }
            }
            assertTrue(hits > 0, doc + " 里找不到 docs/RELEASES/vX.Y.Z.md 链接 —— 锚点失效（不得当成通过）");
        }

        Matcher badge = VERSION_BADGE.matcher(read("README.md"));
        assertTrue(badge.find(), "README.md 里找不到 version 徽章（badge/version-x.y.z-）—— 锚点失效");
        if (!badge.group(1).equals(latest)) {
            problems.add("  README.md 的版本徽章是 " + badge.group(1) + "，最新发布说明是 " + latest);
        }

        assertTrue(problems.isEmpty(),
                "README 未指向最新发布（真值 = docs/RELEASES/ 下最大的版本号）：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("docs/README 索引覆盖 plans / reviews / ADR 的全部文件")
    void indexCoversEveryDocument() throws Exception {
        String index = read("docs/README.md");
        List<String> problems = new ArrayList<>();
        problems.addAll(unindexed(index, "docs/plans", false));
        problems.addAll(unindexed(index, "docs/reviews", false));
        problems.addAll(unindexed(index, "docs/decisions", true));

        assertTrue(problems.isEmpty(),
                "以下文档未登记进 docs/README 的索引（新增文档必须同步登记，否则等于不存在）：\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("文档里的探针项数必须等于探针脚本的 EXPECTED_ITEMS_SSH")
    void documentedProbeItemCountMatchesScript() throws Exception {
        int expected = expectedProbeItems();
        List<String> problems = new ArrayList<>();
        int hits = 0;

        for (String doc : FACT_DOCS) {
            String text = read(doc);
            Matcher matcher = PROBE_ITEMS.matcher(text);
            while (matcher.find()) {
                if (isHistorical(text, matcher.start())) {
                    continue;
                }
                hits++;
                String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                int stated = Integer.parseInt(raw);
                if (stated != expected) {
                    problems.add("  " + doc + " 写「" + matcher.group().trim() + "」，实际期望 " + expected + " 项");
                }
            }
        }

        assertTrue(hits > 0, "现行手册里找不到「N 项探针 / 部署后 N 项探针」表述 —— 锚点失效（不得当成通过）");
        assertTrue(problems.isEmpty(),
                "探针项数与 " + PROBE_SCRIPT + " 的 EXPECTED_ITEMS_SSH（" + expected + "）不一致"
                        + "（真值 = 脚本常量；改了检查项就必须同步文档，否则说明书在说谎）：\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("文档里的「N 条迁移 / N 个迁移」必须等于迁移文件数")
    void documentedMigrationCountMatchesRepository() throws Exception {
        int expected = migrationVersions().size();
        List<String> problems = new ArrayList<>();
        int hits = 0;

        for (String doc : FACT_DOCS) {
            String text = read(doc);
            Matcher matcher = MIGRATION_COUNT.matcher(text);
            while (matcher.find()) {
                if (isHistorical(text, matcher.start())) {
                    continue;
                }
                hits++;
                int stated = Integer.parseInt(matcher.group(1));
                if (stated != expected) {
                    problems.add("  " + doc + " 写「" + matcher.group().trim() + "」，实际 " + expected + " 个");
                }
            }
        }

        assertTrue(hits > 0, "现行手册里找不到「N 条迁移 / N 个迁移」表述 —— 锚点失效（不得当成通过）");
        assertTrue(problems.isEmpty(),
                "迁移条数与 " + MIGRATION_DIR + " 的实际文件数（" + expected + "）不一致：\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("不得把测试条数写成精确值（无法静态对齐的指标只允许下限或指向发布说明）")
    void testCountIsNeverStatedAsExactNumber() throws Exception {
        List<String> problems = new ArrayList<>();

        for (String doc : FACT_DOCS) {
            String text = read(doc);
            Matcher matcher = EXACT_TEST_COUNT.matcher(text);
            while (matcher.find()) {
                if (isHistorical(text, matcher.start())) {
                    continue;
                }
                problems.add("  " + doc + " 写「" + matcher.group().trim() + "」");
            }
        }

        assertTrue(problems.isEmpty(),
                "文档把测试条数写成了精确值。该数字随每次改动变化且 Surefire 报告不入库，"
                        + "写死必然腐烂（2026-10-29 实测 docs/CI.md 仍写「310 个测试」而实际 1111 个）。\n"
                        + "请改成「1100+ 个测试」这类下限形态，或写明「精确条数以 docs/RELEASES/ 最新发布说明为准」：\n"
                        + String.join("\n", problems));
    }

    /** 从探针脚本读 EXPECTED_ITEMS_SSH —— 探针项数的唯一真值源。 */
    private int expectedProbeItems() throws Exception {
        Matcher matcher = Pattern.compile("EXPECTED_ITEMS_SSH=(\\d+)").matcher(SourceText.read(repoFile(PROBE_SCRIPT)));
        assertTrue(matcher.find(), PROBE_SCRIPT + " 里找不到 EXPECTED_ITEMS_SSH 常量 —— 探针项数没有可对齐的真值锚"
                + "（该常量必须先落地，否则本门禁无法判定）");
        int value = Integer.parseInt(matcher.group(1));
        assertTrue(value >= 10, "EXPECTED_ITEMS_SSH=" + value + " 明显过小 —— 解析错位或常量被改坏（不得当成通过）");
        return value;
    }

    /** 匹配点之前的一小段里出现「此前/曾经」等词 → 视为回顾句，不参与判据。 */
    private static boolean isHistorical(String text, int matchStart) {
        int from = Math.max(0, matchStart - 30);
        return HISTORICAL.matcher(text.substring(from, matchStart)).find();
    }

    /** 列出目录下未被索引引用的 .md 文件（{@code adrOnly} 为真时只比 ADR-*.md）。 */
    private List<String> unindexed(String index, String dir, boolean adrOnly) throws Exception {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoFile(dir))) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .filter(name -> !adrOnly || name.startsWith("ADR-"))
                    .sorted()
                    .forEach(name -> {
                        if (!index.contains(name)) {
                            missing.add("  " + dir + "/" + name);
                        }
                    });
        }
        return missing;
    }

    /** 按文件名解析迁移版本号，升序返回。 */
    private List<Integer> migrationVersions() throws Exception {
        List<Integer> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoFile(MIGRATION_DIR))) {
            files.forEach(path -> {
                Matcher matcher = MIGRATION_FILE.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            });
        }
        versions.sort(Comparator.naturalOrder());
        assertTrue(!versions.isEmpty(), MIGRATION_DIR + " 下找不到 V<n>__*.sql —— 解析失效（不得当成通过）");
        return versions;
    }

    /** 取 docs/RELEASES 下版本号最大的一个（按三段数字比较，不按字典序）。 */
    private String latestReleaseVersion() throws Exception {
        List<String> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoFile(RELEASE_DIR))) {
            files.forEach(path -> {
                Matcher matcher = RELEASE_FILE.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    versions.add(matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3));
                }
            });
        }
        assertTrue(!versions.isEmpty(), RELEASE_DIR + " 下找不到 vX.Y.Z.md —— 解析失效（不得当成通过）");
        return versions.stream().max(ManualFactConsistencyContractTest::compareSemver).orElseThrow();
    }

    private static int compareSemver(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private String read(String relativePath) throws Exception {
        return stripHtmlComments(SourceText.read(repoFile(relativePath)));
    }

    /** 把 HTML 注释抹成等长空白（保留长度，避免让基于位置的判据错位）。 */
    private static String stripHtmlComments(String text) {
        Matcher matcher = HTML_COMMENT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            out.append(" ".repeat(matcher.end() - matcher.start()));
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
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
