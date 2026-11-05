package com.intelligentresume.interview.domain;

/**
 * 面试类型，作为上下文的一行写入 prompt（{@code Interview Mode: ...}）。
 *
 * <p>要点：服务端不按它做分支判断，只透传给模型；因此新增取值不需要改流程代码，
 * 但会改变出题风格，需同步前端选项与提示词说明。
 */
public enum InterviewMode { JD_TARGETED, TECHNICAL, BEHAVIORAL, COMPREHENSIVE }
