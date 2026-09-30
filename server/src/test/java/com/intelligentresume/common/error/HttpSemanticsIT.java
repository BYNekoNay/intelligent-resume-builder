package com.intelligentresume.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 语义级调用方错误映射（405 / 415 / 406）：属于「调用方传错」，必须返回 4xx + 统一信封，
 * 不能落入兜底 handler 被报成 500「系统异常」并输出 ERROR 级堆栈——否则调用方无法区分
 * 「自己传错了」和「服务端故障」，且监控里的 API 5xx 告警被自身流量噪声污染。
 *
 * <p>同族的「缺请求头 / 缺参数 / 请求体不可读」已在 GlobalExceptionHandler 中覆盖；
 * 本测试守护剩余的 HTTP 协议类错误。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpSemanticsIT {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("方法不允许（POST 打到 GET-only 端点）返回 405 信封，而非 500")
    void methodNotAllowed_returns405() throws Exception {
        mockMvc.perform(post("/api/system/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()))
                .andExpect(jsonPath("$.message").value("请求方法不受支持"));
    }

    @Test
    @DisplayName("不支持的媒体类型（text/plain 打到 JSON 端点）返回 415 信封，而非 500")
    void unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("username=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()))
                .andExpect(jsonPath("$.message").value("不支持的媒体类型"));
    }

    @Test
    @DisplayName("不可接受的响应媒体类型（Accept: XML 打到 JSON 端点）返回 406 信封，而非 500")
    void notAcceptable_returns406() throws Exception {
        mockMvc.perform(get("/api/system/health").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()))
                .andExpect(jsonPath("$.message").value("不支持的响应媒体类型"));
    }
}