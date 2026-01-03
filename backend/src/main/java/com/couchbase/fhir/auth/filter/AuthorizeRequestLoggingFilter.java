package com.couchbase.fhir.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * Debug-only filter: logs parameters for POST /oauth2/authorize to diagnose 400s.
 */
@Component
public class AuthorizeRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthorizeRequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && 
            "/oauth2/authorize".equals(request.getRequestURI())) {
            log.info("========== POST /oauth2/authorize ==========");
            log.info("[AUTHZ-POST] ALL Parameters:");
            request.getParameterMap().forEach((key, values) -> {
                if (!key.equals("_csrf")) {  // Don't log CSRF token
                    log.info("[AUTHZ-POST]   {}={}", key, Arrays.toString(values));
                }
            });
            log.info("============================================");
        }

        if ("GET".equalsIgnoreCase(request.getMethod()) &&
            "/oauth2/authorize".equals(request.getRequestURI())) {
            String[] patientIdVals = request.getParameterValues("patient_id");
            String[] clientIdVals = request.getParameterValues("client_id");
            String[] scopeVals = request.getParameterValues("scope");
            log.info("[AUTHZ-GET] client_id={} scope={} patient_id={}",
                    clientIdVals == null ? null : Arrays.toString(clientIdVals),
                    scopeVals == null ? null : Arrays.toString(scopeVals),
                    patientIdVals == null ? null : Arrays.toString(patientIdVals));
        }

        filterChain.doFilter(request, response);
    }
}
