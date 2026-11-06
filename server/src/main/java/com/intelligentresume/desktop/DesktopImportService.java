package com.intelligentresume.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.config.DesktopLocalUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 桌面端的 **Web → 桌面工作区迁移**（ADR-012 阶段四 / R27–R29 / R37–R38 / F5）。
 *
 * <p>小白三问：
 * <ul>
 *   <li><b>解决什么业务问题</b>：用户在网页版攒了简历/岗位/投递记录，买了桌面单机版后想把
 *       这些数据整个搬进本地工作区。本类负责导入的「对外契约」：先给用户看预览（新增几条、
 *       跳过几条、哪些冲突），等用户确认后再一次性写入。</li>
 *   <li><b>在链路哪里</b>：{@code DesktopImportController} 调它；它把真正的活分给
 *       {@link DesktopImportPlanner}（分类）和 {@link DesktopImportWriter}（写入）；
 *       数据写入 H2 本地库（嵌入式小数据库，数据就存在本地文件里）。</li>
 *   <li><b>改坏它用户会看到什么</b>：preview 有了副作用 → 用户只是「看一眼预览」
 *       数据就被改了；apply 不在事务内 → 写到一半失败，本地库留下一半 Web 数据的残局；
 *       拒绝策略放宽 → 包里不认识的集合被静默丢弃，用户数据无声消失。</li>
 * </ul>
 *
 * <p>核心判据（R37）：**只按稳定记录 ID 与归一化内容比对**，绝不按文本相似度自动合并 ——
 * 「不同 ID 即便内容很像，也是两条独立记录」。因此本类不引入任何模糊匹配。
 *
 * <p>R38 的顺序是硬约束：**先校验 → 再预览（新增/跳过/冲突）→ 用户确认 → 原子应用**。
 * 取消 = 不调用 apply，工作区一个字节都不动。
 *
 * <p>写入的关键是**主键重映射与外键链**：包里的记录带的是 Web 侧主键，
 * 而本地库的主键是自增的，且投递记录引用的是 Web 侧的 JD / 简历版本 ID。
 * 因此导入按依赖顺序（岗位 → 职业资料 → 简历 → 版本 → 投递 → 状态历史）执行，
 * 用 {@code idMap} 把「Web 主键」映射成「本地主键」，并逐级改写外键。
 *
 * <h2>批次 1.4 起的分工</h2>
 * <ul>
 *   <li>{@link DesktopImportPlanner}：把包翻成**执行计划**（扁平、父在前、子紧随其父，每条带自己的决策）；</li>
 *   <li>{@link DesktopImportWriter}：按计划顺序写入，父记录三种决策都播种 idMap，最后统一回填当前版本指针；</li>
 *   <li>{@link ImportContentComparator}：内容是否相同（含 JSON / 时间 / 数字归一）；</li>
 *   <li>{@link ImportValueMapper}：包字段 → 本地列值，以及全部外键改写规则。</li>
 * </ul>
 * 本类只保留对外的 API 契约与编排。**嵌套子记录（简历版本 / 投递状态历史）是独立条目**：
 * 它们参与预览、逐条分类、逐条解决冲突，不再依附「父记录是否新增」这一分支。
 *
 * <p>改这里的红线：
 * <ul>
 *   <li><b>preview 必须无副作用</b>：只读库、只分类；真正写数据只能在 {@link #apply} 且必须在事务内。</li>
 *   <li><b>apply 会重新 plan 一次</b>，不复用预览结果 —— 预览与写入之间本地库可能变化，写入以最新状态为准。</li>
 *   <li><b>含不支持的集合 → 整份拒绝</b>，不做"跳过未知集合继续导"：静默丢数据无法向用户交代。</li>
 *   <li>{@code resolutions} 的键是 <b>stableId</b>（嵌套记录用自己的），不是本地主键也不是包内 id。</li>
 * </ul>
 */
@Service
public class DesktopImportService {

    private static final Logger log = LoggerFactory.getLogger(DesktopImportService.class);

    private final DesktopImportPlanner planner;
    private final DesktopImportWriter writer;

    public DesktopImportService(JdbcTemplate jdbcTemplate,
                                DesktopLocalUserService localUserService,
                                ObjectMapper objectMapper) {
        ImportColumnBinder columnBinder = new ImportColumnBinder(jdbcTemplate, objectMapper);
        ImportContentComparator comparator = new ImportContentComparator(columnBinder, objectMapper);
        ImportValueMapper valueMapper = new ImportValueMapper(columnBinder, localUserService);
        this.planner = new DesktopImportPlanner(jdbcTemplate, comparator);
        this.writer = new DesktopImportWriter(jdbcTemplate, columnBinder, valueMapper, localUserService);
    }

    /** 一条记录的预览结论。 */
    public enum Decision {
        /** 本地没有 → 新增 */
        ADD,
        /** 本地已有且内容一致 → 跳过 */
        SKIP,
        /** 本地已有但内容变了 → 冲突，交给用户解决 */
        CONFLICT
    }

    /**
     * 一条记录的预览结论。
     *
     * @param collection 集合路径：{@code jobDescriptions} / {@code careerMaterials} / {@code resumes} /
     *                   {@code applications}，嵌套记录为 {@code resumes.versions} / {@code applications.statusHistory}
     * @param entityType 本地表名（与 desktop_import_map.entity_type 同口径）
     */
    public record PreviewItem(String collection,
                              String stableId,
                              Decision decision,
                              String entityType,
                              Long localId,
                              String reason,
                              Map<String, Object> localSnapshot,
                              Map<String, Object> importedSnapshot) {
    }

    /**
     * 预览结果。
     *
     * <p>三个计数**计入嵌套记录**，恒有 {@code added + skipped + conflicts == items.size()}；
     * {@code items} 的顺序即写入顺序（父记录先于其子记录）。
     */
    public record ImportPreview(String packageId,
                                int added,
                                int skipped,
                                int conflicts,
                                List<String> unsupportedCollections,
                                List<PreviewItem> items) {
    }

    /**
     * 一条「预览时判为冲突、apply 时却被跳过」的记录（#77）。
     *
     * <p>apply 会重新 plan：预览到写入之间本地库可能变化，一条预览时 CONFLICT 的记录
     * 到 apply 时可能因本地内容已一致而判 SKIP —— 用户给出的 resolution 会被 SKIP 分支
     * 忽略。这份清单把这件事点名出来，让调用方与用户至少能看到发生了什么，
     * 而不是静默吞掉用户的决定。
     *
     * @param stableId 记录的稳定 ID（与 resolutions 的键同口径）
     * @param collection 集合路径：{@code jobDescriptions} / {@code resumes.versions} 等
     * @param entityType 本地表名（与 desktop_import_map.entity_type 同口径）
     * @param reason 跳过原因（来自 apply 时重新 plan 的判定）
     */
    public record SkippedAfterConflict(String stableId,
                                       String collection,
                                       String entityType,
                                       String reason) {
    }

    /**
     * 应用结果计数。
     *
     * <p>口径：{@code keptLocal} 包含"冲突但没给解决方式"和"给了无法识别的解决方式"两种，
     * 即<b>凡是没写入的冲突都算保留本地</b>；{@code insertedStableIds} 只记新增的条目，可用于前端做二次定位。
     *
     * <p>{@code skippedAfterConflict}（#77）：预览时判 CONFLICT（用户给过 resolution）、
     * apply 时重新 plan 因本地内容已一致而走 SKIP 的记录。判定以「apply 时决策为 SKIP 且
     * resolutions 里有这个 stableId」为准 —— resolutions 的键只会为预览期的冲突而给出。
     */
    public record ImportResult(int inserted,
                               int updated,
                               int skipped,
                               int keptLocal,
                               List<String> insertedStableIds,
                               List<SkippedAfterConflict> skippedAfterConflict) {
    }

    /**
     * 校验并分类，**不写任何数据**（R38：写入前必须先预览）。
     */
    public ImportPreview preview(Map<String, Object> pkg) {
        DesktopImportPlanner.Plan plan = planner.plan(pkg);
        List<PreviewItem> items = plan.entries().stream()
                .map(entry -> new PreviewItem(entry.collectionPath(), entry.stableId(), entry.decision(),
                        entry.table(), entry.localId(), entry.reason(), entry.localSnapshot(), entry.record()))
                .toList();
        int added = (int) items.stream().filter(item -> item.decision() == Decision.ADD).count();
        int skipped = (int) items.stream().filter(item -> item.decision() == Decision.SKIP).count();
        int conflicts = (int) items.stream().filter(item -> item.decision() == Decision.CONFLICT).count();
        return new ImportPreview(plan.packageId(), added, skipped, conflicts,
                plan.unsupportedCollections(), items);
    }

    /**
     * 原子应用导入（R38：确认后一次性写入；中途失败整体回滚）。
     *
     * @param resolutions 每个稳定 ID 的解决方式：
     *                    {@code "KEEP_LOCAL"} / {@code "USE_IMPORTED"}，
     *                    或一个「字段 → 值」的 Map（手动合并，只改这些字段）。
     *                    嵌套记录用它自己的 stableId 作为键。
     */
    @Transactional
    public ImportResult apply(Map<String, Object> pkg, Map<String, Object> resolutions) {
        DesktopImportPlanner.Plan plan = planner.plan(pkg);
        if (!plan.unsupportedCollections().isEmpty()) {
            throw new IllegalStateException("包内含本版不支持的集合：" + plan.unsupportedCollections()
                    + " —— 为避免静默丢数据，整份导入已被拒绝");
        }
        return writer.apply(plan, resolutions == null ? Map.of() : resolutions);
    }
}
