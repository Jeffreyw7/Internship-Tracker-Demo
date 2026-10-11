package com.campus.tracker.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/** Bound blocking body reads; the recovery filter also checks elapsed time between reads. */
@Component
public class RequestReadLimits implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    @Override public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("connectionTimeout","5000");
            connector.setProperty("disableUploadTimeout","false");
            connector.setProperty("connectionUploadTimeout","5000");
            connector.setProperty("maxSwallowSize","8192");
        });
    }
}
