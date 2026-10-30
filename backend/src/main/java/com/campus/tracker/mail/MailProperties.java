package com.campus.tracker.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("campus.mail")
public record MailProperties(String publicOrigin, String sender, String keyId, String encryptionKey,
                             boolean production, boolean workerEnabled, Map<String, String> decryptKeys,
                             String helpDestination) {
    public MailProperties(String publicOrigin, String sender, String keyId, String encryptionKey,
                          boolean production, boolean workerEnabled) {
        this(publicOrigin, sender, keyId, encryptionKey, production, workerEnabled, Map.of(), publicOrigin);
    }
    @ConstructorBinding
    public MailProperties {
        try {
            URI origin = URI.create(publicOrigin);
            if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                    || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                    || !("https".equals(origin.getScheme()) || (!production && "http".equals(origin.getScheme())))) {
                throw new IllegalArgumentException();
            }
            decryptKeys = decryptKeys == null ? Map.of() : Map.copyOf(decryptKeys);
            for (var entry : decryptKeys.entrySet()) {
                if (entry.getKey().isBlank() || entry.getKey().length() > 100
                        || Base64.getDecoder().decode(entry.getValue()).length != 32
                        || (production && (entry.getKey().startsWith("local")
                        || entry.getValue().equals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))) throw new IllegalArgumentException();
            }
            if (helpDestination != null && !helpDestination.isBlank()) {
                URI help = URI.create(helpDestination);
                if (help.getHost() == null || help.getUserInfo() != null || help.getFragment() != null
                        || !("https".equals(help.getScheme()) || (!production && "http".equals(help.getScheme())))) throw new IllegalArgumentException();
            } else if (production) throw new IllegalArgumentException();
            publicOrigin = publicOrigin.replaceAll("/+$", "");
            if (sender == null || !sender.matches("[^\\s<>@]+@[^\\s<>@]+") || keyId == null || keyId.isBlank()
                    || keyId.length() > 100 || Base64.getDecoder().decode(encryptionKey).length != 32
                    || (production && (keyId.startsWith("local") || encryptionKey.equals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            // Configuration exceptions must not echo encryption keys or credentials.
            throw new IllegalArgumentException("Invalid mail configuration");
        }
    }

    @Override public String toString() {
        return "MailProperties[publicOrigin=" + publicOrigin + ", keyId=" + keyId
                + ", production=" + production + ", workerEnabled=" + workerEnabled + "]";
    }
}
