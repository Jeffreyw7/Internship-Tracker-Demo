package com.campus.tracker.export;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("campus.export.s3")
public record ExportS3Properties(@DefaultValue("") String bucket, @DefaultValue("us-east-1") String region,
        URI endpoint, @DefaultValue("false") boolean pathStyle, @DefaultValue("false") boolean allowInsecureEndpoint) {
    public void validate() {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || region == null || !region.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException("S3 export bucket and region are required");
        if (endpoint != null && (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || !("https".equals(endpoint.getScheme()) || allowInsecureEndpoint && "http".equals(endpoint.getScheme()))))
            throw new IllegalArgumentException("S3 endpoint requires HTTPS unless explicitly allowed for isolated local testing");
    }
}
