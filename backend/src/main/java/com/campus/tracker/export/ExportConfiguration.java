package com.campus.tracker.export;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExportProperties.class)
@ConditionalOnProperty(name = "campus.export.enabled", havingValue = "true", matchIfMissing = true)
public class ExportConfiguration {
    @Bean
    @ConditionalOnProperty(name = "campus.export.storage", havingValue = "file", matchIfMissing = true)
    ExportObjectStore fileExportObjectStore(ExportProperties properties, org.springframework.core.env.Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("distributed"))) throw new IllegalArgumentException("Distributed exports require private shared object storage");
        return new FileExportObjectStore(properties.fileRoot());
    }
}
