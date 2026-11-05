package com.intelligentresume.common.api;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/**
 * 全链路 traceId 注入：最高优先级，保证后续所有过滤器/控制器都能拿到同一个 id。
 *
 * <p>行为与约束：
 * <ul>
 *   <li>优先复用请求头 {@code X-Trace-Id}（网关/前端透传），缺失或空白才生成 UUID；</li>
 *   <li>同一 id 写三处：request 属性（业务代码取用）、响应头 {@code X-Trace-Id}（客户端报错时回报）、
 *       MDC（日志格式自动带上）；</li>
 *   <li>MDC 用 try-with-resources 清理，线程池复用线程时不会把上一个请求的 traceId 带进来；</li>
 *   <li>traceId 会进日志与响应，**不得**写入用户信息或任何凭据。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    /** 响应头 / 请求头名：前端报错上报与服务端日志对账都依赖它。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** request 属性名：控制器与异常处理器从这里取。 */
    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    /** 日志 MDC key：logging pattern 里以 %X{traceId} 输出。 */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 建立 trace 上下文并透传。注意顺序：属性/响应头必须在 chain 之前写好，
     * 否则下游（尤其是异常处理器）取不到 id；MDC 必须在 finally 语义内关闭，否则线程复用时串号。
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(TRACE_ID_MDC_KEY, traceId)) {
            chain.doFilter(request, response);
        }
    }
}
