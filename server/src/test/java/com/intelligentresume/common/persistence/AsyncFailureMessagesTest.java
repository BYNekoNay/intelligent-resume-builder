package com.intelligentresume.common.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AsyncFailureMessages} 单元测试（ideation #514）：
 * 失败消息写入 {@code VARCHAR(1024)} 列前的统一截断边界。
 */
class AsyncFailureMessagesTest {

    @Test
    @DisplayName("null 原样返回，保持「清空失败消息」语义")
    void persisted_null_staysNull() {
        assertNull(AsyncFailureMessages.persisted(null));
    }

    @Test
    @DisplayName("未超长（含恰好等于上限）原样返回")
    void persisted_withinLimit_unchanged() {
        String exact = "x".repeat(AsyncFailureMessages.MAX_STORED_LENGTH);

        assertEquals("boom", AsyncFailureMessages.persisted("boom"));
        assertEquals(exact, AsyncFailureMessages.persisted(exact));
    }

    @Test
    @DisplayName("超长截断到上限（严格小于列宽 1024，MySQL 严格模式不再拒绝写入）")
    void persisted_beyondLimit_truncated() {
        String persisted = AsyncFailureMessages.persisted("x".repeat(5000));

        assertEquals(AsyncFailureMessages.MAX_STORED_LENGTH, persisted.length());
    }

    @Test
    @DisplayName("截断点落在代理对中间时不切断增补字符")
    void persisted_surrogatePair_neverSplit() {
        // 前 999 个 ASCII 字符 + 一个 emoji（代理对占 2 个 char）：截断必须回退到 999
        String message = "x".repeat(AsyncFailureMessages.MAX_STORED_LENGTH - 1) + "😀" + "y".repeat(50);

        String persisted = AsyncFailureMessages.persisted(message);

        assertEquals(AsyncFailureMessages.MAX_STORED_LENGTH - 1, persisted.length());
        assertEquals(message.substring(0, AsyncFailureMessages.MAX_STORED_LENGTH - 1), persisted);
        // 末位不是孤立的高代理（Java 字符串中单独的高代理会破坏 UTF-8 编码）
        assertFalse(Character.isHighSurrogate(persisted.charAt(persisted.length() - 1)));
    }
}