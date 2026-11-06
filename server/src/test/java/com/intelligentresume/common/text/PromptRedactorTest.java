package com.intelligentresume.common.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PromptRedactor 契约测试：值级打码是三个脱敏方（素材/沟通/ATS）共用的单一出处，
 * 这里的正则口径与替换顺序（EMAIL → PHONE → URL → ID）被多处测试间接锚定，本测试直接锚定。
 */
class PromptRedactorTest {

    @Test
    @DisplayName("四类敏感值分别替换成对应占位符")
    void masksEachCategory() {
        assertEquals("contact [EMAIL]", PromptRedactor.mask("contact a@b.com"));
        assertEquals("call [PHONE]", PromptRedactor.mask("call 13800138000"));
        assertEquals("see [URL]", PromptRedactor.mask("see https://example.com/x"));
        assertEquals("id [ID]", PromptRedactor.mask("id 110101199001010011"));
    }

    @Test
    @DisplayName("顺序锚定：先邮箱后手机，邮箱内嵌数字串不被手机正则切碎")
    void emailRunsBeforePhone() {
        assertEquals("[EMAIL]", PromptRedactor.mask("a13800138000@example.com"));
    }

    @Test
    @DisplayName("顺序锚定：URL 整段吞掉，URL 内的号码/证件号随整段输出 [URL]")
    void urlAbsorbsEmbeddedSensitiveValues() {
        assertEquals("[URL]", PromptRedactor.mask("https://example.com/110101199001010011"));
        assertEquals("[URL]", PromptRedactor.mask("https://example.com/t/13800138000"));
    }

    @Test
    @DisplayName("组合文本一次打码；null 与空串原样返回")
    void combinedTextAndNullSafety() {
        String text = "mail a@b.com call 13800138000 visit https://example.com id 110101199001010011";
        assertEquals("mail [EMAIL] call [PHONE] visit [URL] id [ID]", PromptRedactor.mask(text));
        assertEquals(null, PromptRedactor.mask(null));
        assertEquals("", PromptRedactor.mask(""));
    }
}
