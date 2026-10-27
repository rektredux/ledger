package com.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS, needed only during local development when the Vite dev server runs on
 * its own port (5173) while the backend runs on 8080.
 *
 * <p>Only localhost / 127.0.0.1 origins are allowed, matching the binding
 * restriction in application.properties. The application has no authentication,
 * so CORS is a complementary boundary rather than a security guarantee: it
 * stops a web page on a different host from making credentialed requests to the
 * backend, but does not replace authentication for remote access.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String frontendBaseUrl;

    public WebConfig(@Value("${ledger.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:3000",
                        "http://127.0.0.1:3000",
                        frontendBaseUrl)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
