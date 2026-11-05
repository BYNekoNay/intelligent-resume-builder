package com.intelligentresume.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 导出文件本地存储服务（按 key 直接落本地磁盘，不接对象存储）。
 *
 * <p>storageKey = UUID 随机生成,不含 userId/简历标题/时间戳等可猜信息。
 * 文件按 key 前两位字符分子目录存储,避免单目录文件过多。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code store} 用 {@code CREATE_NEW}：key 重复会直接失败而不是静默覆盖（UUID 重名视同异常）。</li>
 *     <li>{@code read} 文件不存在返回 {@code null}（由调用方转 404），IO 异常才抛。</li>
 *     <li>{@code delete} 对 {@code null} key 返回 true：没有文件也算"已清理"，便于过期流程幂等。</li>
 *     <li>{@code storageKey} 必须是本服务生成的值，**不能直接来自用户输入**——{@code resolvePath} 只按前两位
 *         拼目录，不做路径规范化。</li>
 *     <li>本类没有并发控制：唯一性靠 {@code CREATE_NEW}，重复写同一 key 会抛 {@code FileAlreadyExistsException}。</li>
 * </ul>
 */
@Service
public class ExportStorageService {

    private static final Logger log = LoggerFactory.getLogger(ExportStorageService.class);

    private final Path root;

    public ExportStorageService(@Value("${app.pdf.output-dir:./pdf-output}") String outputDir) {
        this.root = Path.of(outputDir);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建导出存储目录: " + outputDir, e);
        }
        log.info("ExportStorageService initialized: root={}", root.toAbsolutePath());
    }

    /**
     * 存储 PDF 字节,返回存储信息。
     */
    public StoredFile store(byte[] content, String suffix) {
        String storageKey = UUID.randomUUID() + "." + suffix;
        Path filePath = resolvePath(storageKey);

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("存储导出文件失败: " + storageKey, e);
        }

        String checksum = sha256(content);
        log.debug("Stored export file: key={}, size={}, sha256={}", storageKey, content.length, checksum);
        return new StoredFile(storageKey, content.length, checksum);
    }

    /**
     * 读取存储文件。
     */
    public byte[] read(String storageKey) {
        Path filePath = resolvePath(storageKey);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("读取导出文件失败: " + storageKey, e);
        }
    }

    /**
     * 删除存储文件。
     */
    public boolean delete(String storageKey) {
        if (storageKey == null) return true;
        Path filePath = resolvePath(storageKey);
        try {
            Files.deleteIfExists(filePath);
            log.debug("Deleted export file: {}", storageKey);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete export file: {}", storageKey, e);
            return false;
        }
    }

    /**
     * 清理所有文件(用于测试或维护)。
     */
    public void cleanupAll() {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("Cleanup failed", e);
        }
    }

    private Path resolvePath(String storageKey) {
        // 按前两位字符分子目录: root/ab/abcdef-1234.pdf
        String prefix = storageKey.substring(0, Math.min(2, storageKey.length()));
        return root.resolve(prefix).resolve(storageKey);
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record StoredFile(String storageKey, long size, String checksumSha256) {
    }
}
