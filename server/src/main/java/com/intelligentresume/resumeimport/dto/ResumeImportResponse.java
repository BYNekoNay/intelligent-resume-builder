package com.intelligentresume.resumeimport.dto;
import java.util.Map;
public record ResumeImportResponse(String fileName,String mediaType,String extractedText,Map<String,Object> normalizedResumeInput,boolean originalFileStored){}
