package com.ian.transit.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects the destructive OD-correction admin endpoints with a shared token.
 *
 * The /api/od-correction/** endpoints UPDATE and DELETE curated analysis data,
 * so they must never be callable anonymously. This lightweight filter avoids
 * pulling in full Spring Security for a single admin surface:
 *
 *   - Requests must send header "X-Admin-Token" matching ${admin.api.token}.
 *   - When the property is blank (not configured), ALL requests are rejected
 *     with 503 — fail closed, never fail open.
 *   - Token comparison uses MessageDigest.isEqual (constant time).
 *
 * If the platform later gains authentication requirements beyond this one
 * surface, replace this filter with Spring Security.
 */
@Configuration
public class AdminApiSecurityConfig {

    static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    @Bean
    public FilterRegistrationBean<AdminApiTokenFilter> adminApiTokenFilter(
            @Value("${admin.api.token:}") String adminApiToken
    ) {
        FilterRegistrationBean<AdminApiTokenFilter> registration =
                new FilterRegistrationBean<>(new AdminApiTokenFilter(adminApiToken));
        registration.addUrlPatterns("/api/od-correction/*");
        registration.setOrder(1);
        return registration;
    }

    /** Servlet filter performing the actual token check. */
    static final class AdminApiTokenFilter extends OncePerRequestFilter {

        private static final Logger log = LoggerFactory.getLogger(AdminApiTokenFilter.class);

        private final String configuredToken;

        AdminApiTokenFilter(String configuredToken) {
            this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            if (configuredToken.isEmpty()) {
                log.warn(
                        "Rejected {} {} — ADMIN_API_TOKEN is not configured, admin endpoints are locked",
                        request.getMethod(),
                        request.getRequestURI()
                );
                writeJsonError(
                        response,
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Admin endpoints are disabled: ADMIN_API_TOKEN is not configured"
                );
                return;
            }

            String providedToken = request.getHeader(ADMIN_TOKEN_HEADER);
            if (providedToken == null || !constantTimeEquals(configuredToken, providedToken)) {
                log.warn(
                        "Rejected {} {} — missing or invalid {} header",
                        request.getMethod(),
                        request.getRequestURI(),
                        ADMIN_TOKEN_HEADER
                );
                writeJsonError(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Missing or invalid " + ADMIN_TOKEN_HEADER + " header"
                );
                return;
            }

            filterChain.doFilter(request, response);
        }

        private boolean constantTimeEquals(String expected, String provided) {
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8)
            );
        }

        private void writeJsonError(
                HttpServletResponse response,
                int status,
                String message
        ) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"status\":" + status + ",\"message\":\"" + message + "\"}"
            );
        }
    }
}
