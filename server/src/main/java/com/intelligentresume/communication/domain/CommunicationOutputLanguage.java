package com.intelligentresume.communication.domain;

/**
 * 文案输出语言：简体中文 / 英文。
 *
 * <p>要点：
 * <ul>
 *   <li>请求里可为 null，由 {@code GenerateCommunicationRequest#normalizedLanguage} 兜底成 {@code ZH_CN}。</li>
 *   <li>同时决定模型提示词的语言要求与结果校验的语言闸门（见 {@code CommunicationAiResultValidator}），两处不能只改一处。</li>
 *   <li>以字符串落库在模板表，重命名会让历史模板读不出。</li>
 * </ul>
 */
public enum CommunicationOutputLanguage {
    ZH_CN,
    EN
}
