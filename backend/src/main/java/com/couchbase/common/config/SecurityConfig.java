package com.couchbase.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for Couchbase FHIR Server
 * - Protects /fhir/* endpoints with:
 *   1. API Tokens (user-generated from /tokens page)
 *   2. OAuth 2.0 JWT (SMART on FHIR from Spring Authorization Server)
 * - Protects /api/admin/* endpoints with JWT authentication (Admin UI only)
 * - OAuth 2.0 endpoints handled by AuthorizationServerConfig (@Order(1))
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.jwt.JwtDecoder> jwtDecoderProvider;
    
    @Autowired
    private org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired(required = false)
    private com.couchbase.fhir.auth.SmartOAuthSuccessHandler smartOAuthSuccessHandler;

    @Value("${app.security.use-keycloak:false}")
    private boolean useKeycloak;

    private org.springframework.security.oauth2.jwt.JwtDecoder resolveJwtDecoder() {
        org.springframework.security.oauth2.jwt.JwtDecoder decoder = jwtDecoderProvider.getIfAvailable();
        if (decoder == null) {
            throw new IllegalStateException("No JwtDecoder bean available. Ensure embedded Authorization Server is enabled or set KEYCLOAK_JWKS_URI when Keycloak is enabled.");
        }
        return decoder;
    }

    /**
     * Admin UI and general API filter chain
     * Handles custom JWT for /api/admin/* endpoints
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            // Use AntPathRequestMatcher so this chain applies regardless of MVC servlet mapping
            .securityMatcher("/api/**") // Only match /api/* paths
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Allow open access to Admin UI authentication
                .requestMatchers("/api/auth/**").permitAll()
                
                // Allow open access to initialization endpoints (needed before JWT exists)
                .requestMatchers("/api/admin/initialization/**").permitAll()
                
                // Protect all other Admin UI endpoints - require JWT
                .requestMatchers("/api/admin/**").authenticated()
                
                // Allow open access to other API endpoints
                .requestMatchers("/api/**").permitAll()
            )
            // Use same OAuth2 Resource Server JWT for admin endpoints now
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                .decoder(resolveJwtDecoder())
                .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        
        return http.build();
    }

    /**
     * FHIR API filter chain
     * Handles OAuth2 JWT tokens (both SMART on FHIR and API tokens via client_credentials grant)
     * Allows public access to /fhir/metadata (CapabilityStatement)
     */
    @Bean
    @Order(3)
    public SecurityFilterChain fhirFilterChain(HttpSecurity http) throws Exception {
        // Custom matcher: in some deployments HAPI internally remaps the servlet path so Spring may
        // see just /Patient (etc.). We match if the original URI or servletPath starts with /fhir.
        RequestMatcher fhirRequestMatcher = request -> {
            String uri = request.getRequestURI();
            String servlet = request.getServletPath();
            return uri.startsWith("/fhir") || servlet.startsWith("/fhir");
        };

        http
            .securityMatcher(fhirRequestMatcher)
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Allow public access to SMART discovery under FHIR base
                .requestMatchers("/fhir/.well-known/**").permitAll()
                // Allow public access to metadata endpoint (FHIR CapabilityStatement)
                .requestMatchers("/fhir/metadata").permitAll()
                // Require authentication for all other FHIR endpoints
                .anyRequest().authenticated()
            )
            // OAuth 2.0 Resource Server - validates all OAuth2 JWT tokens
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(resolveJwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter))
            )
            // Disable anonymous authentication
            .anonymous(anonymous -> anonymous.disable());
        
        return http.build();
    }

    /**
     * Default filter chain for all other endpoints
     * Includes form login for OAuth authentication and public SMART discovery
     */
    @Bean
    @Order(4)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF enabled for login form (required by Spring Security form login)
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**") // Disable for REST APIs
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(authz -> authz
                // Allow open access to actuator endpoints (health, metrics)
                .requestMatchers("/actuator/**").permitAll()
                // Allow OAuth authorization server endpoints (authorize, token, jwks, userinfo)
                // Permit internal /oauth2 endpoints only when the embedded Authorization Server is active
                .requestMatchers("/oauth2/**").permitAll() // default allow; Security behavior will be controlled by bean presence
                
                // Allow login pages and static resources
                // /login - legacy/general purpose (kept for compatibility)
                // /oauth2/login - OAuth2 SMART app authentication
                .requestMatchers("/login", "/oauth2/login", "/error", "/css/**", "/js/**").permitAll()
                
                // Allow all other requests (backwards compatibility)
                .anyRequest().permitAll()
            )
            // Enable form login for OAuth authentication
            // Use /oauth2/login to separate from admin UI login
            // Use custom success handler if available (for SMART on FHIR patient picker routing)
            .formLogin(form -> {
                form.loginPage("/oauth2/login").permitAll();
                if (smartOAuthSuccessHandler != null) {
                    form.successHandler(smartOAuthSuccessHandler);
                }
            })
            // Enforce no-cache headers for HTML endpoints
            .headers(headers -> headers.cacheControl(org.springframework.security.config.Customizer.withDefaults()))
            // Enable logout
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );
        
        return http.build();
    }
}
