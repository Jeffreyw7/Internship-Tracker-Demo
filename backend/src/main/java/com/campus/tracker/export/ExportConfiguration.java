package com.campus.tracker.export;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ExportProperties.class, ExportS3Properties.class})
@ConditionalOnProperty(name = "campus.export.enabled", havingValue = "true", matchIfMissing = true)
public class ExportConfiguration {
    @Bean
    @ConditionalOnProperty(name = "campus.export.storage", havingValue = "file", matchIfMissing = true)
    ExportObjectStore fileExportObjectStore(ExportProperties properties, org.springframework.core.env.Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("distributed"))) throw new IllegalArgumentException("Distributed exports require private shared object storage");
        return new FileExportObjectStore(properties.fileRoot());
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "campus.export.storage", havingValue = "s3")
    static class S3 {
        @Bean(destroyMethod = "close")
        software.amazon.awssdk.services.s3.S3Client exportS3Client(ExportS3Properties properties) {
            properties.validate();
            var client = software.amazon.awssdk.services.s3.S3Client.builder()
                    .region(software.amazon.awssdk.regions.Region.of(properties.region())).forcePathStyle(properties.pathStyle())
                    .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder().maxConnections(8)
                            .connectionTimeout(java.time.Duration.ofSeconds(3)).connectionAcquisitionTimeout(java.time.Duration.ofSeconds(3))
                            .socketTimeout(java.time.Duration.ofSeconds(30)))
                    .overrideConfiguration(configuration -> configuration.apiCallTimeout(java.time.Duration.ofSeconds(60))
                            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(30)));
            if (properties.endpoint() != null) client.endpointOverride(properties.endpoint());
            return client.build();
        }
        @Bean ExportObjectStore s3ExportObjectStore(software.amazon.awssdk.services.s3.S3Client client, ExportS3Properties properties) {
            return new S3ExportObjectStore(client, properties.bucket());
        }
    }
    @Bean DownloadAdmission exportDownloadAdmission(@org.springframework.beans.factory.annotation.Value("${campus.export.max-downloads:4}") int maximum) {
        return new DownloadAdmission(maximum);
    }
    @Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    @ConditionalOnProperty(name = "campus.export.worker-enabled", havingValue = "true")
    static class Processing {
        @Bean ExportJobRepository exportJobRepository(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager, java.time.Clock clock) {
            return new ExportJobRepository(jdbc, manager, clock);
        }
        @Bean(destroyMethod = "close") ExportWorker exportWorker(ExportJobRepository jobs, ExportObjectStore objects, CsvWriter csv, ExportProperties properties, java.time.Clock clock) {
            return new ExportWorker(jobs, objects, csv, properties, clock);
        }
        @Bean ExportSchedule exportSchedule(ExportWorker worker) { return new ExportSchedule(worker); }
    }
    static class ExportSchedule {
        private final ExportWorker worker;
        ExportSchedule(ExportWorker worker) { this.worker = worker; }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${campus.export.poll-interval-ms:2000}")
        public void process() { worker.tick(); }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${campus.export.maintenance-interval-ms:60000}", initialDelay = 5000)
        public void cleanup() { worker.maintain(); }
    }
}
