package com.intelligentresume.ai.settings;

/**
 * 密文保护器：把明文凭据变成只能由本机当前用户解开的密文。
 *
 * <p>抽成接口是为了两件事：
 * <ol>
 *   <li><b>可测</b>：{@link DpapiCredentialStore} 的落盘/读回/删除逻辑用假保护器就能全测，
 *       不必在 CI（Linux）上依赖 Windows；</li>
 *   <li><b>可替换</b>：真 DPAPI 在非 Windows 上不可用，调用方需要能判 {@link #available()} 并给出明确失败，
 *       而不是抛一个让人猜的 {@code UnsatisfiedLinkError}。</li>
 * </ol>
 */
public interface SecretProtector {

    /** 当前环境是否真的能用（Windows + JNA 可用）。 */
    boolean available();

    /** 实现名，写进日志与错误信息，便于判断"到底是哪条链路"。 */
    String name();

    byte[] protect(byte[] plaintext, byte[] entropy);

    byte[] unprotect(byte[] ciphertext, byte[] entropy);
}
