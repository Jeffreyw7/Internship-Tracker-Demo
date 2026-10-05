package com.campus.tracker.export;

import java.net.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;

class ExportStorageIT {
    @TempDir Path directory;
    @Test void privateStorageStreamsCompleteCsvAndRejectsAnonymousDownloads() throws Exception {
        String credentials = """
                {"identities":[{"name":"export-tests","credentials":[{"accessKey":"synthetic-access","secretKey":"synthetic-secret-only"}],"actions":["Admin","Read","Write","List"]}]}
                """;
        try (var storage = new GenericContainer<>("chrislusf/seaweedfs:4.48")
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(credentials), "/etc/exports-auth.json")
                .withCommand("server", "-s3", "-s3.config=/etc/exports-auth.json", "-dir=/data", "-ip=127.0.0.1", "-s3.ip.bind=0.0.0.0", "-volume.max=1", "-master.telemetry=false")
                .withExposedPorts(8333).waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))) {
            storage.start();
            URI endpoint = URI.create("http://" + storage.getHost() + ":" + storage.getMappedPort(8333));
            try (var client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic-access", "synthetic-secret-only"))).build()) {
                client.createBucket(request -> request.bucket("export-tests"));
                var objects = new S3ExportObjectStore(client, "export-tests");
                String key = "jobs/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".csv";
                Path source = directory.resolve("source.csv"); Files.writeString(source, "\uFEFFCompany\r\n\"quoted\"\r\n");
                objects.put(key, source);
                try (var input = objects.open(key)) { assertThat(input.readAllBytes()).isEqualTo(Files.readAllBytes(source)); }
                var anonymous = (HttpURLConnection) endpoint.resolve("/export-tests/" + key).toURL().openConnection();
                anonymous.setConnectTimeout(5000); anonymous.setReadTimeout(5000);
                assertThat(anonymous.getResponseCode()).isEqualTo(403); anonymous.disconnect();
                assertThatThrownBy(() -> objects.open("../../other-account.csv")).isInstanceOf(IllegalArgumentException.class);
                objects.delete(key); objects.delete(key);
                assertThatThrownBy(() -> objects.open(key)).isInstanceOf(java.io.IOException.class);
            }
        }
    }
}
