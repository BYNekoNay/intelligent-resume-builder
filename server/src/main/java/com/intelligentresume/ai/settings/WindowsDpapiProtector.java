package com.intelligentresume.ai.settings;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Windows DPAPI 实现（ADR-012 D8）。
 *
 * <p>用 {@code CryptProtectData} / {@code CryptUnprotectData}（用户级，默认不传
 * {@code CRYPTPROTECT_LOCAL_MACHINE}）：密文只能由**同一台机器的同一个用户**解开。
 * 这正是我们要的语义 —— 备份文件被拷走、或同机其他账号捡到，都解不出密钥。
 *
 * <p>绑定 {@code entropy}：调用方传入「应用 + 用户」派生的值，避免别的程序用同一个 DPAPI
 * 直接解我们的密文（DPAPI 的 entropy 是额外的口令成分）。
 *
 * <p><b>非 Windows 上不可用</b>：{@link #available()} 返回 false，上层据此给出明确错误，
 * 而不是让 {@code UnsatisfiedLinkError} 冒到接口层。CI 跑在 Linux 上，所以这里必须——
 * 而不是"应该"——有一个可判定的分支。
 */
@Component
class WindowsDpapiProtector implements SecretProtector {

    private static final Logger log = LoggerFactory.getLogger(WindowsDpapiProtector.class);

    @Override
    public boolean available() {
        return Platform.isWindows();
    }

    @Override
    public String name() {
        return "dpapi-user-scope";
    }

    @Override
    public byte[] protect(byte[] plaintext, byte[] entropy) {
        requireUsable();
        return Crypt32Util.cryptProtectData(plaintext, entropy, 0, null, null);
    }

    @Override
    public byte[] unprotect(byte[] ciphertext, byte[] entropy) {
        requireUsable();
        return Crypt32Util.cryptUnprotectData(ciphertext, entropy, 0, null);
    }

    private void requireUsable() {
        if (!available()) {
            // 不静默降级成明文：宁可功能不可用，也不要用户以为密钥被加密了
            throw new IllegalStateException(
                    "本机不是 Windows，DPAPI 不可用（当前实现名 " + name() + "）");
        }
    }

    void logAvailability() {
        log.info("本地 AI 凭据保护器：{}，可用={}", name(), available());
    }
}
