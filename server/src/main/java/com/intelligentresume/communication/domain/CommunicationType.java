package com.intelligentresume.communication.domain;

/**
 * 沟通文案的载体类型：求职信 / 邮件 / 开场白。
 *
 * <p>要点：
 * <ul>
 *   <li>只有 {@code EMAIL} 允许并必须带 subject（见 {@code CommunicationAiResultValidator}）。</li>
 *   <li>以字符串存库，重命名枚举值会让历史数据反序列化失败。</li>
 *   <li>与场景 {@link TemplateScene} 是两个独立维度：同一类型可服务多种场景。</li>
 * </ul>
 */
public enum CommunicationType { COVER_LETTER, EMAIL, OPENING_MESSAGE }
