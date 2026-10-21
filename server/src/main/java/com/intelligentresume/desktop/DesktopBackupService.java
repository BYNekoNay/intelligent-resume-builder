package com.intelligentresume.desktop;

import com.intelligentresume.config.DesktopModeProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 桌面本地工作区的**备份与恢复**（ADR-012 阶段三 / R30 / R39 / F6）。
 *
 * <p>备份形态：H2 的 {@code SCRIPT TO} 导出的 SQL 文本（含全部表结构、数据与
 * {@code flyway_schema_history}），落在数据目录的 {@code backups/} 下。
 * 选它而不是复制 {@code .mv.db} 文件的原因：数据库文件在运行时被连接占用，
 * 而 SQL 脚本可以被**校验**（{@code SCRIPT FROM} 只解析不执行），
 * 这正好支撑 R39 要求的「无效快照不得改变当前工作区」。
 *
 * <p>R39 的三条硬约束在这里都有落点：
 * <ul>
 *   <li>恢复前**先创建当前工作区的安全备份**（{@link #restore} 的第二步）；</li>
 *   <li>快照**先校验后应用**，校验不过直接拒绝，工作区一个字节都不动；</li>
 *   <li>应用过程中失败会用安全备份**回滚**，保证工作区保持可用。</li>
 * </ul>
 *
 * <p><b>AI 凭据不在备份里</b>（R34）：AI 设置是本地配置，不存在工作区库里，
 * 备份自然不含它；恢复后用户需自行重新配置。
 */
@Service
public class DesktopBackupService {

    private static final Logger log = LoggerFactory.getLogger(DesktopBackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String SAFETY_PREFIX = "safety-";
    private static final String BACKUP_PREFIX = "backup-";

    private final JdbcTemplate jdbcTemplate;
    private final DesktopModeProperties properties;
    private final EntityManager entityManager;

    public DesktopBackupService(JdbcTemplate jdbcTemplate,
                                DesktopModeProperties properties,
                                EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.entityManager = entityManager;
    }

    /** 备份清单里的一项。 */
    public record BackupInfo(String id,
                             String fileName,
                             long sizeBytes,
                             String createdAt,
                             boolean safetyBackup) {
    }

    /** 恢复结果。 */
    public record RestoreResult(String restoredId,
                                String safetyBackupId,
                                boolean ok,
                                String message) {
    }

    private Path backupDir() {
        return Path.of(properties.getDataDir()).toAbsolutePath().resolve("backups");
    }

    /** 列出可用快照（最新的在前）。 */
    public List<BackupInfo> listBackups() {
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<BackupInfo> result = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".sql")) {
                    continue;
                }
                result.add(new BackupInfo(
                        name.substring(0, name.length() - 4),
                        name,
                        sizeOf(file),
                        createdAtOf(file),
                        name.startsWith(SAFETY_PREFIX)));
            }
            result.sort(Comparator.comparing(BackupInfo::createdAt).reversed());
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取备份目录 " + dir + "：" + ex.getMessage(), ex);
        }
    }

    /**
     * 创建一份工作区快照。
     *
     * @param label  快照说明（可空）
     * @param safety 是否为「安全备份」（恢复前自动创建的那种）
     */
    public BackupInfo createBackup(String label, boolean safety) {
        Path dir = backupDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建备份目录 " + dir + "：" + ex.getMessage(), ex);
        }
        String stamp = LocalDateTime.now().format(STAMP);
        String base = (safety ? SAFETY_PREFIX : BACKUP_PREFIX) + stamp;
        Path target = dir.resolve(base + ".sql");
        int counter = 1;
        while (Files.exists(target)) {
            target = dir.resolve(base + "-" + counter++ + ".sql");
        }
        try {
            jdbcTemplate.execute("SCRIPT TO '" + sqlPath(target) + "'");
            log.info("桌面工作区备份已创建：{}（{} 字节）", target.getFileName(), sizeOf(target));
            return new BackupInfo(stripSuffix(target.getFileName().toString()),
                    target.getFileName().toString(), sizeOf(target), createdAtOf(target), safety);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("创建备份失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 用指定快照**替换**当前工作区（R39）。
     *
     * <p>顺序不可换：先校验 → 再建安全备份 → 才动当前工作区。
     */
    public RestoreResult restoreBackup(String backupId) {
        Path dir = backupDir();
        Path source = dir.resolve(backupId + ".sql");
        if (!Files.isRegularFile(source)) {
            // 快照不存在：工作区一个字节都不动
            return new RestoreResult(backupId, null, false, "快照不存在：" + source.getFileName());
        }

        // ① 校验：把快照放到一个**一次性临时库**里真跑一遍。
        //    不能用 `SCRIPT FROM`（H2 的 SCRIPT FROM 是「从表导出」，不是读文件），
        //    也不能直接 RUNSCRIPT（那会在当前库执行，校验就变成了破坏）。
        //    独立临时库既能验证语法，也能验证 DDL 真的能建出来。
        try {
            validateSnapshot(source);
        } catch (RuntimeException ex) {
            log.warn("拒绝恢复无效快照 {}：{}", source.getFileName(), ex.getMessage());
            return new RestoreResult(backupId, null, false,
                    "快照无效（无法执行），已保持当前工作区不变：" + ex.getMessage());
        }

        // ② 安全备份：恢复前必须留下一份当前状态
        BackupInfo safety = createBackup("恢复前自动创建", true);

        // ③ 应用：先清空当前库，再执行快照脚本（快照含建表与数据，含 flyway 历史）
        try {
            jdbcTemplate.execute("DROP ALL OBJECTS");
            jdbcTemplate.execute("RUNSCRIPT FROM '" + sqlPath(source) + "'");
            evictCaches();
            log.info("桌面工作区已恢复到快照 {}（安全备份 {}）", source.getFileName(), safety.fileName());
            return new RestoreResult(backupId, safety.fileName(), true,
                    "已恢复到快照 " + source.getFileName() + "；恢复前的安全备份为 " + safety.fileName());
        } catch (RuntimeException ex) {
            // ④ 应用失败：用安全备份把工作区救回来，绝不留下半恢复的库
            log.error("恢复快照 {} 失败，正在用安全备份 {} 回滚", source.getFileName(), safety.fileName(), ex);
            rollback(safety);
            return new RestoreResult(backupId, safety.fileName(), false,
                    "恢复失败并已回滚到安全备份：" + ex.getMessage());
        }
    }

    /** 在一次性临时库里执行快照，确认它真的可用；用完即删。 */
    private void validateSnapshot(Path source) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("snapshot-validate");
            String tempUrl = "jdbc:h2:file:" + tempDir.toAbsolutePath().toString().replace('\\', '/')
                    + "/validate;MODE=MySQL";
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(tempUrl, "sa", "");
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("RUNSCRIPT FROM '" + sqlPath(source) + "'");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("快照无法执行：" + ex.getMessage(), ex);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private void deleteDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响正确性
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private void rollback(BackupInfo safety) {
        try {
            Path file = backupDir().resolve(safety.fileName());
            jdbcTemplate.execute("DROP ALL OBJECTS");
            jdbcTemplate.execute("RUNSCRIPT FROM '" + sqlPath(file) + "'");
            evictCaches();
            log.warn("已用安全备份 {} 回滚当前工作区", safety.fileName());
        } catch (RuntimeException rollbackFailure) {
            log.error("安全备份回滚也失败，工作区可能不完整：{}", rollbackFailure.getMessage(), rollbackFailure);
        }
    }

    /** 恢复会重建全部表结构，必须清掉持久化上下文与二级缓存，否则仍能读到旧数据。 */
    private void evictCaches() {
        if (entityManager == null) {
            return;
        }
        entityManager.clear();
        EntityManagerFactory factory = entityManager.getEntityManagerFactory();
        if (factory != null && factory.getCache() != null) {
            factory.getCache().evictAll();
        }
    }

    /** H2 的 SCRIPT/RUNSCRIPT 用单引号包裹路径，且不接受反斜杠。 */
    private String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private String createdAtOf(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(),
                    java.time.ZoneId.systemDefault()).format(STAMP);
        } catch (IOException ex) {
            return "";
        }
    }

    private String stripSuffix(String fileName) {
        return fileName.endsWith(".sql")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }
}
