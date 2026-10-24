package com.intelligentresume.ai.settings;

import com.intelligentresume.config.DesktopModeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加密凭据落盘逻辑（不依赖 Windows：用假保护器）。
 *
 * <p>DPAPI 本身由 {@link WindowsDpapiProtectorTest} 真实验证（仅 Windows 执行）；
 * 这里守的是**存储层**：路径、原子写、按用户隔离的 entropy、删除、以及"保护器不可用时必须报错
 * 而不是明文落盘"。
 */
class LocalAiCredentialStoreTest {

    /** 假保护器：XOR + 固定前缀。够用来验证"存进去的不是明文"和"entropy 参与运算"。 */
    private static final class FakeProtector implements SecretProtector {
        private final boolean available;

        FakeProtector(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String name() {
            return "fake-xor";
        }

        @Override
        public byte[] protect(byte[] plaintext, byte[] entropy) {
            return xor(plaintext, entropy);
        }

        @Override
        public byte[] unprotect(byte[] ciphertext, byte[] entropy) {
            return xor(ciphertext, entropy);
        }

        private byte[] xor(byte[] input, byte[] entropy) {
            // 先把 entropy 折成一个 32 位哈希再做密钥流。**不能**把 entropy 逐字节异或：
            // 短明文只会用到 entropy 的前几个字节，而 userId 在末尾，跨用户改名就区分不出来。
            // （真 DPAPI 会对整个 entropy 做哈希，假实现必须模拟这个语义，否则测出来的是假的隔离。）
            int hash = 0x811c9dc5;
            for (byte b : entropy) {
                hash = (hash ^ (b & 0xff)) * 0x01000193;
            }
            byte[] out = new byte[input.length];
            for (int i = 0; i < input.length; i++) {
                out[i] = (byte) (input[i] ^ ((hash >>> ((i % 4) * 8)) & 0xff));
            }
            return out;
        }
    }

    private LocalAiCredentialStore store(Path dataDir, boolean available) {
        DesktopModeProperties properties = new DesktopModeProperties();
        properties.setDataDir(dataDir.toString());
        return new LocalAiCredentialStore(properties, new FakeProtector(available));
    }

    @Test
    void savesEncryptedAndLoadsBack(@TempDir Path dataDir) throws Exception {
        LocalAiCredentialStore store = store(dataDir, true);
        store.save(7L, "sk-secret-value");

        Path file = dataDir.resolve("ai-credentials").resolve("user-7.bin");
        assertTrue(Files.isRegularFile(file), "密文应写在 dataDir/ai-credentials 下");

        // 磁盘上不能是明文（否则 DPAPI 等于没做）
        String onDisk = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(onDisk.contains("sk-secret-value"), "落盘内容里不应出现明文密钥");

        assertEquals(Optional.of("sk-secret-value"), store.load(7L));
        assertTrue(store.exists(7L));
    }

    @Test
    void isolatesUsersByEntropy(@TempDir Path dataDir) throws Exception {
        LocalAiCredentialStore store = store(dataDir, true);
        store.save(1L, "key-one");
        // 把 A 的密文改名成 B 的文件名：entropy 含 userId，解出来必然不是 A 的 key
        Path a = dataDir.resolve("ai-credentials").resolve("user-1.bin");
        Path b = dataDir.resolve("ai-credentials").resolve("user-2.bin");
        Files.copy(a, b);

        Optional<String> loaded = store.load(2L);
        assertTrue(loaded.isEmpty() || !"key-one".equals(loaded.get()),
                "跨用户改名后不应解出原用户密钥，实际：" + loaded);
    }

    @Test
    void deleteRemovesFile(@TempDir Path dataDir) {
        LocalAiCredentialStore store = store(dataDir, true);
        store.save(3L, "k");
        store.delete(3L);
        assertFalse(store.exists(3L));
        assertTrue(store.load(3L).isEmpty());
    }

    @Test
    void noTempFileLeftBehind(@TempDir Path dataDir) throws Exception {
        LocalAiCredentialStore store = store(dataDir, true);
        store.save(4L, "k");
        try (var files = Files.list(dataDir.resolve("ai-credentials"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "原子写用的 .tmp 必须被移走，不能留残留");
        }
    }

    @Test
    void refusesToSaveWhenProtectorUnavailable(@TempDir Path dataDir) {
        LocalAiCredentialStore store = store(dataDir, false);
        assertFalse(store.isAvailable());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.save(5L, "k"));
        // 不静默降级为明文：错误信息要说清为什么
        assertTrue(failure.getMessage().contains("DPAPI") || failure.getMessage().contains("非 Windows"),
                "不可用时要给出可读原因，实际：" + failure.getMessage());
        assertFalse(store.exists(5L));
    }

    @Test
    void rejectsBlankSecret(@TempDir Path dataDir) {
        LocalAiCredentialStore store = store(dataDir, true);
        assertThrows(IllegalArgumentException.class, () -> store.save(6L, "  "));
    }
}
