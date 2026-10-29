package com.campus.tracker.application.importing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operators may tighten resource limits; protocol/schema ceilings cannot be raised. */
@Component
public record ImportInputLimits(
        @Value("${campus.imports.file-bytes:2097152}") int fileBytes,
        @Value("${campus.imports.options-bytes:262144}") int optionsBytes,
        @Value("${campus.imports.request-bytes:3145728}") int requestBytes,
        @Value("${campus.imports.data-records:500}") int dataRecords,
        @Value("${campus.imports.source-columns:32}") int sourceColumns,
        @Value("${campus.imports.cell-bytes:65536}") int cellBytes,
        @Value("${campus.imports.header-bytes:256}") int headerBytes,
        @Value("${campus.imports.preview-work-ms:30000}") int previewWorkMs,
        @Value("${campus.imports.parse-work-ms:10000}") int parseWorkMs,
        @Value("${campus.imports.upload-read-ms:5000}") int uploadReadMs,
        @Value("${campus.imports.process-preview-count:4}") int processPreviewCount) {
    public ImportInputLimits() { this(2097152,262144,3145728,500,32,65536,256,30000,10000,5000,4); }
    @Autowired
    public ImportInputLimits {
        bounded("file-bytes",fileBytes,2097152); bounded("options-bytes",optionsBytes,262144);
        bounded("request-bytes",requestBytes,3145728); bounded("data-records",dataRecords,500);
        bounded("source-columns",sourceColumns,32); bounded("cell-bytes",cellBytes,65536);
        bounded("header-bytes",headerBytes,256); bounded("preview-work-ms",previewWorkMs,30000);
        bounded("parse-work-ms",parseWorkMs,10000); bounded("upload-read-ms",uploadReadMs,5000);
        bounded("process-preview-count",processPreviewCount,4);
    }
    private static void bounded(String property,int value,int maximum) {
        if(value<1 || value>maximum) throw new IllegalArgumentException("campus.imports."+property+" must be between 1 and "+maximum+".");
    }
}
