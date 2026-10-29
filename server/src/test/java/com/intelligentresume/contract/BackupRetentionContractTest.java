package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备份保留策略的结构契约门禁（P3 批次）。
 *
 * <p>背景：原清理逻辑是裸 {@code find -mtime +14 -delete} —— 连续失败 15 天会把**最后一份
 * 好备份也删光**，之后"保留 14 天"形同虚设。修复为「新→旧排序 → 跳过最新 MIN_KEEP 份 →
 * 其余中超过保留期的才删」。本门禁用"禁止形态"而非"必须有形态"：将来任何人把裸
 * {@code -mtime +N -delete} 写回去都会红，且不依赖具体实现写法。
 */
class BackupRetentionContractTest {

    private static final String SCRIPT = "deploy/backup-database.sh";

    @Test
    @DisplayName("MIN_KEEP 保底份数必须存在、默认 ≥1，且 --self-test 自带非法值拒绝")
    void minKeepFloorExistsAndIsValidated() throws Exception {
        String script = read(SCRIPT);
        assertTrue(Pattern.compile("MIN_KEEP=\"\\$\\{MIN_KEEP:-\\d+\\}\"").matcher(script).find(),
                SCRIPT + " 缺少 MIN_KEEP 定义（形如 MIN_KEEP=\"${MIN_KEEP:-3}\"）—— "
                        + "没有保底份数，备份连续失败会连最后一份好备份一起删光");
        assertTrue(Pattern.compile("\\[ \"\\$MIN_KEEP\" -ge 1 \\]").matcher(script).find(),
                SCRIPT + " 缺少 MIN_KEEP ≥1 的合法性拒绝 —— MIN_KEEP=0 等于没有保底，"
                        + "等于把原始缺陷重新开门");
        assertTrue(script.contains("--self-test"),
                SCRIPT + " 缺少 --self-test —— 清理逻辑不自带可运行的反向验证，"
                        + "下一次改坏只能靠真删了备份才发现");
    }

    @Test
    @DisplayName("清理必须是『先排序、跳过保底、再按保留期删』的结构")
    void retentionSortsBeforeTruncating() throws Exception {
        String script = stripComments(read(SCRIPT));
        assertTrue(script.contains("sort -rn") && script.contains("tail -n +"),
                SCRIPT + " 的清理逻辑缺少『新→旧排序 + 跳过前 MIN_KEEP 份』结构 —— "
                        + "保底份数是通过排序后截断实现的，两个环节缺一不可");
    }

    @Test
    @DisplayName("禁止裸 -mtime +N -delete 形态（那是保底缺陷的原始写法）")
    void bareMtimeDeleteIsForbidden() throws Exception {
        String script = stripComments(read(SCRIPT));
        // 注释必须先抹白：脚本文档里要写"禁止 -mtime +N -delete"来说明为什么，不过滤会假红
        assertFalse(Pattern.compile("-mtime[^\\n]*-delete").matcher(script).find(),
                SCRIPT + " 出现裸 -mtime +N -delete —— 这正是『连续失败 15 天删光最后一份好备份』的"
                        + "原始形态。清理必须走 cleanup_backups（排序 → 跳过 MIN_KEEP → 按保留期删）");
    }

    /** 抹白整行注释与 CR；脚本里的行内注释极少，整行抹白已覆盖文档性注释。 */
    private String stripComments(String text) {
        return text.replaceAll("(?m)^\\s*#.*$", "").replace("\r", "");
    }

    private String read(String relative) throws Exception {
        return SourceText.read(repoFile(relative));
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private java.nio.file.Path repoFile(String relative) throws Exception {
        java.nio.file.Path serverRoot = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        java.nio.file.Path target = java.nio.file.Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(java.nio.file.Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
