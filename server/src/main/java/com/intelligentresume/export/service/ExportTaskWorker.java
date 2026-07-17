package com.intelligentresume.export.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExportTaskWorker {

    private final ExportService exportService;

    public ExportTaskWorker(ExportService exportService) {
        this.exportService = exportService;
    }

    @Scheduled(fixedDelayString = "${app.pdf.worker.poll-interval-ms:1000}")
    public void poll() {
        exportService.processPendingExports();
    }

    @Scheduled(fixedDelayString = "${app.pdf.cleanup-interval-ms:600000}")
    public void cleanupExpired() {
        exportService.cleanupExpired();
    }
}
