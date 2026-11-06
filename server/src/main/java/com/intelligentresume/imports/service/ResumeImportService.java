package com.intelligentresume.imports.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.imports.dto.ResumeImportResponse;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 简历导入解析：把上传的 TXT / PDF / DOCX 抽成纯文本，再粗略归一化成基础字段。
 *
 * <p>用大白话说：用户不想手动录入旧简历，就上传文件让系统"读"出来。步骤是：
 * ①四道校验闸门拦住不合规文件 → ②按扩展名选解析器抽纯文本 → ③正则启发式
 * 猜出姓名/邮箱/电话 → ④把"原文 + 猜出来的字段建议"一起返回给前端，
 * 用户确认后才真正写进资料库。本类只到"给建议"为止，不落库、不落盘、不调 AI。
 *
 * <p>在链路里的位置：{@code ResumeImportController} 的唯一入口调它；
 * 产出给前端确认表单，确认后由用户手动写入 career_material/personalprofile。
 *
 * <p>改坏它用户会看到什么：四道闸门少一道，用户可能上传超大文件拖垮服务、
 * 或用改扩展名的文件绕过格式限制；异常处理改成直接抛出，会把解析库的内部
 * 报错和文件路径泄露给浏览器。
 *
 * <p>职责边界：只做"文件 → 文本 → 建议字段"，不落库、不落盘、不合并进资料库；
 * 结果要经用户确认后才写库（见包注释）。
 *
 * <p>要点：
 * <ul>
 *   <li>四道闸门按顺序生效：非空 → 字节上限（{@code max-bytes}）→ 扩展名与
 *       Content-Type 白名单 → 抽取后字符上限（{@code max-text-characters}）。
 *       前两道拦"传太大"，第三道拦"改扩展名"，第四道拦"小文件炸出大文本"；</li>
 *   <li>抽取在<b>单线程池 + 超时</b>里执行：畸形 PDF/DOCX 可能长时间占住线程，
 *       multipart 的字节上限管不到解析耗时；超时按业务错误返回，不暴露解析器栈信息；</li>
 *   <li>{@code normalize} 只是启发式：姓名取第一行非空、邮箱/手机用正则首个命中、
 *       summary 塞全文。抽错字段很常见，所以 {@code normalizedResumeInput} 只能当建议；</li>
 *   <li>返回的 {@code originalFileStored} 恒为 false —— 原文件从不保存，不要改成 true
 *       除非同时补齐落盘加密与清理策略；</li>
 *   <li>线程池是 daemon 且 {@code @PreDestroy} 关闭，禁用它会让应用无法优雅停机。</li>
 * </ul>
 */
@Service
public class ResumeImportService {
    private static final String OCTET_STREAM = "application/octet-stream";
    /**
     * 扩展名 → 允许的 Content-Type 白名单。
     * octet-stream 是浏览器/命令行不识别类型时的通用兜底；docx 放行 application/zip
     * 是因为部分客户端把 OOXML（本质是 zip 包）报成 zip。放宽任何一项都等于放宽
     * "扩展名与内容不符"的防线。
     */
    private static final Map<String, Set<String>> MEDIA_TYPES = Map.of(
            "txt", Set.of("text/plain", OCTET_STREAM),
            "pdf", Set.of("application/pdf", OCTET_STREAM),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/zip", OCTET_STREAM));
    /** 只取<b>第一个</b>命中，正文里出现多个邮箱/电话时不区分主次。 */
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    /** 国内手机号（可带 +86/空格），用前后负向断言避免从更长数字串中截出 11 位。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private final long maxBytes;
    private final int maxPdfPages;
    private final int maxTextCharacters;
    private final long extractTimeoutMs;
    /** 解析执行器：单线程 + 超时中断，避免畸形文件（深对象图/超长页）占住请求线程。 */
    private final ExecutorService extractExecutor;

    public ResumeImportService(@Value("${app.resume-import.max-bytes:5242880}") long maxBytes,
                               @Value("${app.resume-import.max-pdf-pages:60}") int maxPdfPages,
                               @Value("${app.resume-import.max-text-characters:200000}") int maxTextCharacters,
                               @Value("${app.resume-import.extract-timeout-ms:15000}") long extractTimeoutMs) {
        this.maxBytes = maxBytes;
        this.maxPdfPages = maxPdfPages;
        this.maxTextCharacters = maxTextCharacters;
        this.extractTimeoutMs = extractTimeoutMs;
        AtomicInteger sequence = new AtomicInteger();
        this.extractExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resume-import-extract-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdownExtractExecutor() {
        extractExecutor.shutdownNow();
    }

    /**
     * 解析入口。步骤：①非空校验；②字节上限（5MB）；③扩展名+Content-Type 白名单
     * （两道必须同时命中，防改扩展名骗过校验）；④在带超时的独立线程里抽文本
     * （畸形文件可能让解析库卡死，超时 15 秒强制放弃）；⑤字符上限+清洗空字符；
     * ⑥启发式归一化出建议字段。四道闸门的顺序就是执行顺序：先便宜的（看大小/名字）
     * 再贵的（真去解析），别反过来。
     *
     * <p>异常处理只有两种归宿：业务异常原样透传（消息面向用户），其他一律压成
     * "文件内容无法解析"，避免把 PDFBox/POI 的内部异常与路径信息漏给客户端。
     * 注意文件名字段仅用于回显，不参与落盘路径拼接。
     */
    public ResumeImportResponse parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION, "文件不能为空");
        // 字节上限：只约束上传体积，不约束解析后的文本量（见下方字符上限）。
        if (file.getSize() > maxBytes) throw new BusinessException(ErrorCode.VALIDATION, "文件不能超过 5 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("resume");
        String extension = Optional.ofNullable(StringUtils.getFilenameExtension(name)).orElse("").toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.containsKey(extension)) throw new BusinessException(ErrorCode.VALIDATION, "仅支持 TXT、PDF 或 DOCX 文件");
        // Content-Type 缺失时按 octet-stream 处理；扩展名与类型必须同时命中白名单，
        // 防止"改扩展名"绕过格式约束。该判断只看声明值，真正的格式校验由解析器抛错兜底。
        String mediaType = Optional.ofNullable(file.getContentType()).filter(value -> !value.isBlank()).orElse(OCTET_STREAM);
        if (!MEDIA_TYPES.get(extension).contains(mediaType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件扩展名与内容类型不匹配");
        }
        try {
            String text = extractWithTimeout(extension, file);
            // #17：文本长度上限——入口 5MB 只能约束文件字节，纯文本可能远超可编辑/可送模型的合理体量
            if (text.length() > maxTextCharacters) {
                throw new BusinessException(ErrorCode.VALIDATION, "文件文本过长，请上传精简版简历");
            }
            String normalizedText = text.replace("\u0000", "").trim();
            if (normalizedText.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "未能从文件中提取文本");
            return new ResumeImportResponse(name, mediaType,
                    normalizedText, normalize(normalizedText), false);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件内容无法解析");
        }
    }

    /**
     * #17：解析在独立线程执行并受超时约束（畸形 PDF/DOCX 可能长时间占住请求线程，
     * 且 multipart 的 5MB 上限管不到解析耗时）。超时按业务错误返回，不暴露解析器细节。
     */
    private String extractWithTimeout(String extension, MultipartFile file) throws Exception {
        Future<String> future = extractExecutor.submit(() -> extract(extension, file));
        try {
            return future.get(extractTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new BusinessException(ErrorCode.VALIDATION, "文件解析超时，请更换文件后重试");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        }
    }

    /**
     * 实际抽取实现。独立成 protected 方法是为了让超时用例能确定性触发
     * （真实文件在快机器上可能在毫秒级完成，靠耗时碰运气的测试会不稳定）。
     */
    protected String extract(String extension, MultipartFile file) throws Exception {
        return switch (extension) {
            case "txt" -> new String(file.getBytes(), StandardCharsets.UTF_8);
            case "pdf" -> extractPdf(file);
            case "docx" -> extractDocx(file);
            default -> throw new IllegalStateException();
        };
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getBytes())) {
            // #17：页数上限——超长 PDF 的文本抽取是 CPU 与内存的主要放大项
            if (document.getNumberOfPages() > maxPdfPages) {
                throw new BusinessException(ErrorCode.VALIDATION, "PDF 页数过多，请上传精简版简历");
            }
            return new PDFTextStripper().getText(document);
        }
    }
    /**
     * DOCX 抽取：只拼接正文段落，空段落丢弃。
     * 表格、页眉页脚、文本框、图片里的文字都取不到 —— 版式化的简历会明显丢内容，
     * 这是"导入结果只是建议"的原因之一，不是可以为空就报错的场景。
     */
    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            return document.getParagraphs().stream().map(p -> p.getText()).filter(s -> !s.isBlank()).collect(Collectors.joining("\n"));
        }
    }
    /**
     * 启发式归一化，输出结构固定为 {@code {"basics": {...}}}：
     * 姓名取第一行非空（PDF 常见排版，TXT 可能取到"个人简历"这类标题）、
     * 邮箱/手机各取首个正则命中、summary 直接塞全文。
     * 只放"抽到的字段"：没抽到 name 时给空串，email/phone 缺键而非空值，
     * 前端需要按缺键判空。不要在这里补 AI 生成，导入流程不调用模型。
     */
    private Map<String, Object> normalize(String text) {
        Map<String, Object> basics = new LinkedHashMap<>();
        basics.put("name", Arrays.stream(text.split("\\R")).map(String::trim).filter(s -> !s.isBlank()).findFirst().orElse(""));
        Matcher email = EMAIL.matcher(text); if (email.find()) basics.put("email", email.group());
        Matcher phone = PHONE.matcher(text); if (phone.find()) basics.put("phone", phone.group());
        basics.put("summary", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basics", basics);
        return result;
    }
}
