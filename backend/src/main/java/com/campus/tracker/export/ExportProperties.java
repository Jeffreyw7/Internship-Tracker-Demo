package com.campus.tracker.export;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("campus.export")
public record ExportProperties(@DefaultValue("2") int maxActive, @DefaultValue("104857600") long maxBytes,
        @DefaultValue("24h") Duration retention, @DefaultValue("file") String storage,
        @DefaultValue("./data/exports") Path fileRoot, @DefaultValue("./data/export-spool") Path spoolRoot,
        @DefaultValue("false") boolean workerEnabled) {
    public ExportProperties {
        if (maxActive < 1 || maxActive > 10 || maxBytes < 1024 || maxBytes > 1073741824L || retention == null
                || retention.compareTo(Duration.ofHours(1)) < 0 || retention.compareTo(Duration.ofDays(7)) > 0
                || !java.util.List.of("file", "s3").contains(storage) || fileRoot == null || spoolRoot == null)
            throw new IllegalArgumentException("Invalid export limits or storage configuration");
    }
}
