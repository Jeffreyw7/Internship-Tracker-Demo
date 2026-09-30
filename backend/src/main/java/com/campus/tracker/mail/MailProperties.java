package com.campus.tracker.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.util.Base64;

@ConfigurationProperties("campus.mail")
public record MailProperties(String publicOrigin, String sender, String keyId, String encryptionKey,
                             boolean production, boolean workerEnabled) {
    public MailProperties {
        try {
            URI origin = URI.create(publicOrigin);
            if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                    || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                    || !("https".equals(origin.getScheme()) || (!production && "http".equals(origin.getScheme())))) {
                throw new IllegalArgumentException();
            }
            publicOrigin = publicOrigin.replaceAll("/+$", "");
            if (sender == null || !sender.matches("[^\\s<>@]+@[^\\s<>@]+") || keyId == null || keyId.isBlank()
                    || keyId.length() > 100 || Base64.getDecoder().decode(encryptionKey).length != 32
                    || (production && (keyId.startsWith("local") || encryptionKey.equals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            // Configuration exceptions must not echo encryption keys or credentials.
            throw new IllegalArgumentException("Invalid verification mail configuration");
        }
    }

    @Override public String toString() {
        return "MailProperties[publicOrigin=" + publicOrigin + ", keyId=" + keyId
                + ", production=" + production + ", workerEnabled=" + workerEnabled + "]";
    }
}
