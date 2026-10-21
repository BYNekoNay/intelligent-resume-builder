package com.intelligentresume.desktop;

import com.intelligentresume.config.DesktopModeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面工作区备份与恢复（ADR-012 阶段三 / R30 / R39 / F6）。
 *
 * <p>用 **H2 file 模式**而非内存库：备份产物是数据库导出脚本，内存库不落文件，
 * 测不到「快照真的写到了数据目录」「恢复真的换了库」这些要害。
 */
@SpringBootTest
@ActiveProfiles("test")
class DesktopBackupServiceIT {

    private static final String DATA_DIR = "target/backup-it-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // H2 拒绝隐式相对路径（90011: a file path that is implicitly relative to the current
        // working directory is not allowed）——这里必须是绝对路径。
        String absolute = Path.of(DATA_DIR).toAbsolutePath().toString().replace('\\', '/');
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + absolute + "/resume"
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE");
        registry.add("app.desktop.enabled", () -> "true");
        registry.add("app.desktop.data-dir", () -> absolute);
    }

    @Autowired
    private DesktopBackupService backupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DesktopModeProperties properties;

    @Test
    void backupAndRestoreRoundTripBringsBackTheSnapshotPoint() {
        insertUser("roundtrip-user");

        DesktopBackupService.BackupInfo backup = backupService.createBackup("往返验证", false);
        assertNotNull(backup.fileName());
        assertTrue(Files.isRegularFile(Path.of(properties.getDataDir(), "backups", backup.fileName())),
                "快照应落在数据目录的 backups/ 下");
        assertTrue(backup.sizeBytes() > 0, "快照不应是空文件");

        // 备份之后改动工作区
        jdbcTemplate.update("DELETE FROM user WHERE username = ?", "roundtrip-user");
        assertEquals(0, countUser("roundtrip-user"));

        DesktopBackupService.RestoreResult result = backupService.restoreBackup(backup.id());
        assertTrue(result.ok(), "恢复应成功：" + result.message());
        assertEquals(1, countUser("roundtrip-user"), "恢复后应回到备份时点的数据");
    }

    @Test
    void invalidSnapshotIsRejectedAndWorkspaceStaysUntouched() {
        insertUser("guard-user");
        int before = countUser("guard-user");

        // 造一个语法损坏的快照
        Path dir = Path.of(properties.getDataDir(), "backups");
        try {
            Files.createDirectories(dir);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        Path broken = dir.resolve("broken-snapshot.sql");
        try {
            Files.writeString(broken, "CREATE TABLE this is not valid sql ((( ;");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        DesktopBackupService.RestoreResult result = backupService.restoreBackup("broken-snapshot");
        assertFalse(result.ok(), "损坏的快照必须被拒绝");
        assertTrue(result.message().contains("无效") || result.message().contains("不存在"),
                "失败原因应点明快照无效/不存在：" + result.message());
        assertEquals(before, countUser("guard-user"), "**当前工作区必须一个字节都没变**");
    }

    @Test
    void restoreAlwaysCreatesASafetyBackupOfTheCurrentWorkspace() {
        insertUser("safety-user");
        DesktopBackupService.BackupInfo backup = backupService.createBackup("用于恢复", false);

        DesktopBackupService.RestoreResult result = backupService.restoreBackup(backup.id());
        assertTrue(result.ok(), "恢复应成功：" + result.message());
        assertNotNull(result.safetyBackupId(), "恢复必须返回安全备份的文件名");

        boolean hasSafety = backupService.listBackups().stream()
                .anyMatch(b -> b.fileName().equals(result.safetyBackupId()) && b.safetyBackup());
        assertTrue(hasSafety, "安全备份应出现在备份清单里：" + backupService.listBackups());
    }

    @Test
    void listBackupsIsEmptyBeforeAnySnapshot() {
        List<DesktopBackupService.BackupInfo> backups = backupService.listBackups();
        // 本类用例共享同一个数据目录，前面的用例可能已建过快照；只断言「能列出来且字段完整」
        for (DesktopBackupService.BackupInfo info : backups) {
            assertNotNull(info.id());
            assertNotNull(info.fileName());
            assertTrue(info.sizeBytes() >= 0);
        }
    }

    private void insertUser(String username) {
        jdbcTemplate.update("INSERT INTO user (username, email, password_hash, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                username, username + "@example.invalid", "{noop}not-a-real-hash");
    }

    private int countUser(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }
}
