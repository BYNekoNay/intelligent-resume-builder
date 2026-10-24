package com.intelligentresume.ai.settings;

import com.intelligentresume.config.DesktopModeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 本地 AI 凭据的加密落盘（ADR-012 D8 / R34）。
 *
 * <p>存放位置：{@code <dataDir>/ai-credentials/user-<id>.bin}，**刻意不在工作区数据库里**。
 * 理由不是洁癖：备份是整库 {@code SCRIPT TO}、导出包按集合打包，密钥一旦入库就必然随包出境，
 * 直接违反 R34「凭据不得进备份 / Web 导出包 / 桌面迁移包」。
 *
 * <p>写盘用「临时文件 + 原子移动」：先写 {@code .tmp} 再 {@code ATOMIC_MOVE}，
 * 避免掉电/崩溃留下半个密文（半份密文比没有更糟 —— 读的时候会解密失败且无法区分原因）。
 *
 * <p>entropy 绑定「应用 + 用户」：同一用户的不同 id 之间也不通用，防止把 A 的密文改名当 B 的用。
 */
@Component
public class LocalAiCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(LocalAiCredentialStore.class);

    private static final String DIR_NAME = "ai-credentials";

    private final DesktopModeProperties properties;
    private final SecretProtector protector;

    LocalAiCredentialStore(DesktopModeProperties properties, SecretProtector protector) {
        this.properties = properties;
        this.protector = protector;
    }

    /** 当前环境能否安全保存凭据（非 Windows 恒为 false）。 */
    public boolean isAvailable() {
        return protector.available();
    }

    public String protectorName() {
        return protector.name();
    }

    public void save(Long userId, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        requireAvailable();
        byte[] cipher = protector.protect(secret.getBytes(StandardCharsets.UTF_8), entropy(userId));
        Path target = fileOf(userId);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, cipher);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("写加密凭据失败：" + target, exception);
        }
    }

    public Optional<String> load(Long userId) {
        FileCheck check = checkFile(userId);
        if (!check.exists()) {
            return Optional.empty();
        }
        requireAvailable();
        try {
            byte[] cipher = Files.readAllBytes(check.path());
            byte[] plain = protector.unprotect(cipher, entropy(userId));
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException("读加密凭据失败：" + check.path(), exception);
        }
    }

    public void delete(Long userId) {
        deleteQuietly(fileOf(userId));
    }

    public boolean exists(Long userId) {
        return checkFile(userId).exists();
    }

    // ------------------------------------------------------------------ 内部

    private void requireAvailable() {
        if (!protector.available()) {
            throw new IllegalStateException(
                    "本机无法安全保存 AI 凭据：当前保护器 " + protector.name()
                            + " 在非 Windows 环境不可用（ADR-012 D8 要求 DPAPI，不做明文降级）");
        }
    }

    private Path fileOf(Long userId) {
        return Path.of(properties.getDataDir()).toAbsolutePath().normalize()
                .resolve(DIR_NAME)
                .resolve("user-" + userId + ".bin");
    }

    private record FileCheck(Path path, boolean exists) {
    }

    private FileCheck checkFile(Long userId) {
        Path path = fileOf(userId);
        return new FileCheck(path, Files.isRegularFile(path));
    }

    private byte[] entropy(Long userId) {
        return ("intelligent-resume:local-ai:" + userId).getBytes(StandardCharsets.UTF_8);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("删除加密凭据失败：{}", path, exception);
        }
    }
}
