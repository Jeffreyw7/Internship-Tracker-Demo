package com.campus.tracker.application.importing;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

@Configuration
public class ImportTransportConfiguration {
    @Bean StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                // Error handling must never reparse a rejected or partially consumed upload.
                return request.getDispatcherType() != jakarta.servlet.DispatcherType.ERROR
                        && !ImportUploadPolicy.isImport(request) && super.isMultipart(request);
            }
        };
    }
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> importUploadConnector(ImportInputLimits limits) {
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol))
                throw new IllegalStateException("Imports require a verified HTTP/1.1 Tomcat connector");
            protocol.setDisableUploadTimeout(false);
            protocol.setConnectionUploadTimeout(limits.uploadReadMs());
            protocol.setMaxSwallowSize(0);
        });
    }
}
