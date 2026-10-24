package com.intelligentresume.ai.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Windows DPAPI 往返（ADR-012 D8）。
 *
 * <p>这类测试**只能**在 Windows 上跑：CI 是 Linux，会整类跳过。
 * 所以要说清口径 —— <b>「DPAPI 真的能用」这件事由本机（Windows）验证，CI 只验证存储层</b>，
 * 不要把 CI 的绿当成 DPAPI 已验证。
 *
 * <p>三个断言各有含义：
 * <ol>
 *   <li>往返一致 —— DPAPI 真被调通了（而不是被我们自己 xor 掉）；</li>
 *   <li>密文 ≠ 明文 —— 落盘的确实不是可读密钥；</li>
 *   <li>换 entropy 解不开 —— entropy 真的是口令成分，改名/改用户拿不到原值。</li>
 * </ol>
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiProtectorTest {

    private final WindowsDpapiProtector protector = new WindowsDpapiProtector();

    @Test
    void roundTripsThroughRealDpapi() {
        assertTrue(protector.available(), "Windows 上 DPAPI 应可用");
        byte[] plain = "sk-test-1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] entropy = "intelligent-resume:local-ai:1".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = protector.protect(plain, entropy);
        assertNotEquals(new String(plain, StandardCharsets.UTF_8),
                new String(cipher, StandardCharsets.ISO_8859_1), "密文不应等于明文");

        byte[] restored = protector.unprotect(cipher, entropy);
        assertArrayEquals(plain, restored, "DPAPI 往返必须还原出原文");

        // 人类 DPAPI 的密文通常更长（含头部与校验），至少不该被压成 0 字节
        assertTrue(cipher.length > 0, "密文不应为空");
    }

    @Test
    void differentEntropyCannotDecrypt() {
        byte[] plain = "belongs-to-user-1".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = protector.protect(plain, "entropy-1".getBytes(StandardCharsets.UTF_8));

        // 用别的 entropy 解：DPAPI 会失败（Crypt32 返回 false → JNA 抛异常），
        // 绝不能"碰巧解出一个垃圾值"还当成功
        assertThrows(Exception.class,
                () -> protector.unprotect(cipher, "entropy-2".getBytes(StandardCharsets.UTF_8)),
                "entropy 不同必须解密失败");
    }

    @Test
    void nameIsStableForLogs() {
        // 名字会写进接口响应的 credentialStoreName 与日志，改了要能一眼看出来
        assertFalse(protector.name().isBlank());
        assertTrue(protector.name().contains("dpapi"));
    }
}
