package com.intelligentresume.interview.domain;

/**
 * 面试的简历来源。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #PLATFORM_RESUME}：引用平台内的 {@code resumeVersionId}，需要校验归属且不能是归档版本。</li>
 *   <li>{@link #EXTERNAL_RESUME}：直接存用户粘贴的全文 {@code externalResumeText}，无归属校验。</li>
 *   <li>两者互斥：创建会话时只会写其中一个字段，另一个置 null，读取侧不要假定两者都存在。</li>
 * </ul>
 */
public enum InterviewSourceType { PLATFORM_RESUME, EXTERNAL_RESUME }
