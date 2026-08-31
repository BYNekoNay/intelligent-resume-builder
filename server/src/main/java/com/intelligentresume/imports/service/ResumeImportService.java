package com.intelligentresume.imports.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.imports.dto.ResumeImportResponse;
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
    public ResumeImportService(@Value("${app.resume-import.max-bytes:5242880}") long maxBytes) { this.maxBytes = maxBytes; }

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
            String text = switch (extension) {
                case "txt" -> new String(file.getBytes(), StandardCharsets.UTF_8);
                case "pdf" -> extractPdf(file);
                case "docx" -> extractDocx(file);
                default -> throw new IllegalStateException();
            };
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

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getBytes())) { return new PDFTextStripper().getText(document); }
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
