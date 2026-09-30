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

@Service
public class ResumeImportService {
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final Map<String, Set<String>> MEDIA_TYPES = Map.of(
            "txt", Set.of("text/plain", OCTET_STREAM),
            "pdf", Set.of("application/pdf", OCTET_STREAM),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/zip", OCTET_STREAM));
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
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

    public ResumeImportResponse parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION, "文件不能为空");
        if (file.getSize() > maxBytes) throw new BusinessException(ErrorCode.VALIDATION, "文件不能超过 5 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("resume");
        String extension = Optional.ofNullable(StringUtils.getFilenameExtension(name)).orElse("").toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.containsKey(extension)) throw new BusinessException(ErrorCode.VALIDATION, "仅支持 TXT、PDF 或 DOCX 文件");
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
    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            return document.getParagraphs().stream().map(p -> p.getText()).filter(s -> !s.isBlank()).collect(Collectors.joining("\n"));
        }
    }
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
