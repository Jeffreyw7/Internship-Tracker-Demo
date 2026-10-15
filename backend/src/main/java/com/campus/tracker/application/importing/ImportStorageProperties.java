package com.campus.tracker.application.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ImportStorageProperties(
        @Value("${campus.imports.owner-preview-count:5}") int ownerPreviewCount,
        @Value("${campus.imports.global-preview-count:100}") int globalPreviewCount,
        @Value("${campus.imports.owner-preview-bytes:41943040}") long ownerPreviewBytes,
        @Value("${campus.imports.global-preview-bytes:838860800}") long globalPreviewBytes,
        @Value("${campus.imports.owner-receipt-count:2000}") int ownerReceiptCount,
        @Value("${campus.imports.global-receipt-count:100000}") int globalReceiptCount,
        @Value("${campus.imports.owner-receipt-bytes:67108864}") long ownerReceiptBytes,
        @Value("${campus.imports.global-receipt-bytes:2147483648}") long globalReceiptBytes,
        @Value("${campus.imports.owner-metadata-count:300}") int ownerMetadataCount,
        @Value("${campus.imports.global-metadata-count:10000}") int globalMetadataCount) {
    public ImportStorageProperties {
        if(ownerPreviewCount<1 || globalPreviewCount<1 || ownerPreviewBytes<1 || globalPreviewBytes<1 || ownerReceiptCount<1 || globalReceiptCount<1 || ownerReceiptBytes<1 || globalReceiptBytes<1 || ownerMetadataCount<1 || globalMetadataCount<1)
            throw new IllegalArgumentException("Import quotas must be positive.");
    }
}
