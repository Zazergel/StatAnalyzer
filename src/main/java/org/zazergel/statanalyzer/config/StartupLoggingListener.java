package org.zazergel.statanalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;


@Component
public class StartupLoggingListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupLoggingListener.class);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String protocol = "http";
        if (env.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }

        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "/");
        String hostAddress = "localhost";

        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("The host name could not be determined, using `localhost` as fallback");
        }

        log.info("""
                        
                        ----------------------------------------------------------
                        \tПриложение '{}' запущено! Доступ:
                        \tЛокальный: \t\t{}://localhost:{}{}
                        \tВнешний: \t{}://{}:{}{}
                        ----------------------------------------------------------
                        """,
                env.getProperty("spring.application.name", "StatAnalyzer"),
                protocol, port, contextPath,
                protocol, hostAddress, port, contextPath);
    }
}
