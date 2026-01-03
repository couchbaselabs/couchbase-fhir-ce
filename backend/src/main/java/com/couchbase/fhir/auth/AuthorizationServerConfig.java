package com.couchbase.fhir.auth;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.oauth.service.OAuthClientService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.auth.repository.CouchbaseRegisteredClientRepository;
import com.couchbase.fhir.auth.repository.CompositeRegisteredClientRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * OAuth 2.0 Authorization Server Configuration for SMART on FHIR
 * 
 * Provides OAuth 2.0 endpoints:
 * - /oauth2/authorize - Authorization endpoint
 * - /oauth2/token - Token endpoint
 * - /oauth2/introspect - Token introspection
 * - /oauth2/revoke - Token revocation
 * - /.well-known/oauth-authorization-server - Server metadata
 * - /oauth2/jwks - JSON Web Key Set
 */
@Configuration
@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "false", matchIfMissing = true)
public class AuthorizationServerConfig {

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationServerConfig.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "config";
    private static final String JWK_DOCUMENT_ID = "oauth-signing-key";
    private static final String DEFAULT_CONNECTION = "default";

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private SmartOAuthSuccessHandler smartOAuthSuccessHandler;
    
    @Autowired
    private com.couchbase.fhir.auth.filter.AuthorizeRequestLoggingFilter authorizeRequestLoggingFilter;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    // The RSA signing key - generated on startup, persisted during initialization
    private volatile RSAKey signingKey = null;
    
    /**
     * Initialize the OAuth signing key after Couchbase connection is established
     * Called explicitly by ConfigurationStartupService after connection succeeds
     * Strategy:
     * 1. Try to load from fhir.Admin.config (if bucket initialized)
     * 2. If not found, generate new key in-memory
     * 3. During initialization (Step 11), persist the key to Couchbase
     * 4. On next restart, load the persisted key
     */
    public void initializeSigningKey() {
        logger.debug("🔐 Initializing OAuth signing key...");
        
        // Try to load existing key from bucket (if initialized)
        if (isConfigCollectionAvailable()) {
            try {
                RSAKey loadedKey = loadKeyFromCouchbase();
                if (loadedKey != null) {
                    this.signingKey = loadedKey;
                    logger.info("✅ Loaded existing OAuth signing key from fhir.Admin.config (kid: {})", loadedKey.getKeyID());
                    return;
                }
            } catch (Exception e) {
                logger.warn("⚠️ Failed to load key from Couchbase: {} - will generate new key", e.getMessage());
            }
        }
        
        // No persisted key found - generate new key
        try {
            RSAKey newKey = generateRsaKey();
            this.signingKey = newKey;
            logger.info("✅ Generated new OAuth signing key (kid: {})", newKey.getKeyID());
            logger.warn("⚠️ Key is in-memory only - will NOT survive restart until FHIR bucket is initialized");
        } catch (Exception e) {
            logger.error("❌ Failed to generate OAuth signing key: {}", e.getMessage(), e);
            throw new IllegalStateException("Cannot start without OAuth signing key", e);
        }
    }
    
    /**
     * Get the current signing key (for persistence during initialization)
     */
    public RSAKey getCurrentKey() {
        return signingKey;
    }
    
    /**
     * Called after a new key is persisted to Couchbase during initialization
     * This is a no-op now since we already have the key in memory
     */
    public void invalidateKeyCache() {
        // No-op: key is already in memory and doesn't need reloading
        logger.debug("🔄 Key cache invalidation called (no-op - key already in memory)");
    }

    // FhirServerConfig was previously used for deriving issuer; logic now moved to authorizationServerSettings
    // Remove unused injection to avoid warning.

    /**
     * SMART Configuration Public Access Filter Chain
     * MUST come before Authorization Server chain (@Order(0) < @Order(1))
     * Allows public access to SMART configuration endpoint per SMART spec
     */
    @Bean
    @Order(0)
    public SecurityFilterChain smartConfigPublicAccessFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/.well-known/smart-configuration", "/fhir/.well-known/smart-configuration")
            .authorizeHttpRequests(authz -> authz.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        
        return http.build();
    }
    
    /**
     * OAuth 2.0 Authorization Server Security Filter Chain
     * Uses Spring's standard configuration pattern
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
            throws Exception {
        // Apply Spring Authorization Server's default configuration.
        // This ensures ALL standard endpoints are correctly mapped, including:
        // - /oauth2/authorize
        // - /oauth2/token
        // - /oauth2/introspect
        // - /oauth2/revoke
        // - /oauth2/jwks
        // - /.well-known/oauth-authorization-server
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        // Enable OpenID Connect and set custom consent page
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
            .oidc(Customizer.withDefaults())
            .authorizationEndpoint(authorization -> authorization
                .consentPage("/consent")
                .authorizationRequestConverter(new SmartAuthorizationRequestAuthenticationConverter())
            );

        // Configure request cache to preserve SavedRequest throughout the OAuth flow
        // CRITICAL: Setting matchingRequestParameterName to null prevents the SavedRequest 
        // from being removed after the first read, which is essential for SMART flows that 
        // need to access OAuth parameters multiple times (SmartOAuthSuccessHandler → ConsentController → Spring Auth Server)
        org.springframework.security.web.savedrequest.HttpSessionRequestCache requestCache = 
            new org.springframework.security.web.savedrequest.HttpSessionRequestCache();
        requestCache.setMatchingRequestParameterName(null);
        http.requestCache(cache -> cache.requestCache(requestCache));
        
        // Redirect to OAuth login page when not authenticated for HTML requests
        http.exceptionHandling((exceptions) -> exceptions
                .defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                )
        );
        
        // Configure form login for OAuth authorization flow
        // Use custom success handler to intercept and redirect to patient picker if needed
        // Use /oauth2/login to separate from admin login flow
        http.formLogin(form -> form
                .loginPage("/oauth2/login")
                .successHandler(smartOAuthSuccessHandler)
                .permitAll()
        );

        // Add logging for POST /oauth2/authorize
        http.addFilterBefore(authorizeRequestLoggingFilter,
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        
        // Accept access tokens for User Info and/or Client Registration
        http.oauth2ResourceServer((oauth2) -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Register OAuth 2.0 clients.
     * In-memory now provides ONLY the built-in administrative client (admin-ui).
     * SMART application clients are persisted in Couchbase and loaded via OAuthClientService.
     * Removed legacy dev test-client for hardening.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(
        OAuthClientService oauthClientService,
        @Value("${admin.ui.client.secret:}") String adminUiClientSecretProp,
        @Value("${admin.ui.client.id:admin-ui}") String adminUiClientId,
        @Value("${admin.ui.scopes:system/*.*,user/*.*}") String adminUiScopesProp,
        @Value("${app.baseUrl}") String configBaseUrl,
        @Value("${frontend.dev.port:3000}") int frontendDevPort) {
    // Derive static admin-ui client secret:
    // Priority: explicit property -> env var ADMIN_UI_CLIENT_SECRET -> fallback test-secret (NOT for production).
    String envSecret = System.getenv("ADMIN_UI_CLIENT_SECRET");
    String rawAdminSecret = (adminUiClientSecretProp != null && !adminUiClientSecretProp.isBlank()) ? adminUiClientSecretProp
        : (envSecret != null && !envSecret.isBlank()) ? envSecret
        : "change-me-admin-ui-secret";
    if ("change-me-admin-ui-secret".equals(rawAdminSecret)) {
        logger.warn("⚠️ Using fallback admin-ui client secret. Set ADMIN_UI_CLIENT_SECRET or admin.ui.client.secret property for production.");
    }

    // Parse scopes (comma or space separated)
    String[] adminScopesArr = adminUiScopesProp.split("[ ,]+");

    // Static admin client for interactive logins (client_credentials only)
    RegisteredClient adminClient = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId(adminUiClientId)
        .clientSecret(passwordEncoder.encode(rawAdminSecret))
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scopes(s -> {
            for (String sc : adminScopesArr) if (!sc.isBlank()) s.add(sc.trim());
        })
        .tokenSettings(TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofHours(
                Long.parseLong(System.getProperty("oauth.token.expiry.hours",
                    System.getenv().getOrDefault("OAUTH_TOKEN_EXPIRY_HOURS", "24"))))
            )
            .build())
        .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
        .build();

    // Derive issuer (strip trailing /fhir if present) for potential future use (SMART dynamic redirects)
    String issuer = configBaseUrl.endsWith("/fhir") ? configBaseUrl.substring(0, configBaseUrl.length() - 5) : configBaseUrl;
    URI issuerUri = URI.create(issuer);
    String host = issuerUri.getHost() == null ? "localhost" : issuerUri.getHost();
    logger.debug("OAuth issuer resolved for admin-ui: issuer={} host={}", issuer, host);

    // Create in-memory repository for built-in admin client ONLY
    InMemoryRegisteredClientRepository inMemoryRepo = 
        new InMemoryRegisteredClientRepository(java.util.List.of(adminClient));
        
        // Create Couchbase repository for SMART apps
        CouchbaseRegisteredClientRepository couchbaseRepo = 
            new CouchbaseRegisteredClientRepository(oauthClientService);
        
        // Combine both repositories (in-memory checked first, then Couchbase)
        logger.info("🔗 Initializing composite RegisteredClientRepository (in-memory + Couchbase)");
        return new CompositeRegisteredClientRepository(inMemoryRepo, couchbaseRepo);
    }

    /**
     * Wrap the default OAuth2AuthorizationService to inject patient_id from session
     * into the authorization attributes when the authorization code is issued.
     */
    @Bean
    public OAuth2AuthorizationService authorizationService() {
        // Create the default in-memory authorization service
        org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService inMemoryService = 
            new org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService();
        
        // Wrap it with our patient-context-aware service
        return new PatientContextAwareAuthorizationService(inMemoryService);
    }

    /**
     * User details for OAuth authentication
     * 
     * IMPORTANT: This bean is intentionally NOT defined here.
     * Spring will auto-wire the CouchbaseUserDetailsService (annotated with @Service("couchbaseUserDetailsService"))
     * which loads users from fhir.Admin.users collection.
     * 
     * Users must exist in Couchbase with:
     * - status: "active"
     * - authMethod: "local"
     * - passwordHash: BCrypt hashed password
     * - role: "admin", "developer", or "smart_user"
     * 
     * For backward compatibility during development, you can create a test user via:
     * POST /api/admin/users with:
     * {
     *   "id": "fhiruser@example.com",
     *   "username": "FHIR User",
     *   "email": "fhiruser@example.com",
     *   "password": "password",
     *   "role": "smart_user",
     *   "authMethod": "local"
     * }
     */
    // UserDetailsService bean now provided by CouchbaseUserDetailsService
    // @Bean public UserDetailsService userDetailsService() { ... }

    // PasswordEncoder bean now provided by PasswordEncoderConfig (separate config to avoid circular dependencies)
    // @Bean public PasswordEncoder passwordEncoder() { ... }

    /**
     * JWT token customizer to add SMART scopes and custom claims
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            String tokenType = context.getTokenType().getValue();
            
            if (tokenType.equals("access_token") || tokenType.equals("id_token")) {
                
                String username = context.getPrincipal().getName();
                
                // Fetch user from Couchbase to get fhirUser reference
                com.couchbase.admin.users.model.User user = null;
                try {
                    Cluster cluster = connectionService.getConnection("default");
                    if (cluster != null) {
                        com.couchbase.client.java.Collection usersCollection = cluster.bucket("fhir")
                            .scope("Admin")
                            .collection("users");
                        user = usersCollection.get(username).contentAs(com.couchbase.admin.users.model.User.class);
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ Could not fetch user {} from Couchbase: {}", username, e.getMessage());
                }
                
                // Add token type for access tokens (hardening)
                if (context.getTokenType().getValue().equals("access_token")) {
                    context.getClaims().claim("token_type", "oauth");
                    
                    // Convert scopes to space-separated string (OAuth 2.0 standard)
                    String scopeString = String.join(" ", context.getAuthorizedScopes());
                    logger.debug("🎫 [TOKEN-CUSTOMIZER] Creating access token with authorized scopes: {}", context.getAuthorizedScopes());
                    logger.debug("🎫 [TOKEN-CUSTOMIZER] Scope string in JWT: {}", scopeString);
                    context.getClaims().claim("scope", scopeString);
                }
                
                // Check for patient_id injected by PatientContextAwareAuthorizationService
                String selectedPatientId = null;
                org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization = context.getAuthorization();
                if (authorization != null) {
                    logger.debug("🔍 [TOKEN-CUSTOMIZER] Checking OAuth2Authorization for patient_id...");
                    
                    // Read patient_id directly from attributes (injected when authorization was saved)
                    Object patientIdAttr = authorization.getAttribute("patient_id");
                    if (patientIdAttr != null) {
                        selectedPatientId = patientIdAttr.toString();
                        if (selectedPatientId.startsWith("Patient/")) {
                            selectedPatientId = selectedPatientId.substring(8);
                        }
                        context.getClaims().claim("patient", selectedPatientId);
                        logger.info("🏥 [TOKEN-CUSTOMIZER] Added patient claim '{}' from authorization attributes", selectedPatientId);
                    } else {
                        logger.debug("[TOKEN-CUSTOMIZER] No patient_id found in authorization attributes");
                    }
                } else {
                    logger.warn("⚠️ [TOKEN-CUSTOMIZER] OAuth2Authorization is null!");
                }
                
                // Always add fhirUser claim if user has a FHIR resource reference
                // This is independent of patient context - practitioner can select a different patient
                if (user != null && user.getFhirUser() != null && !user.getFhirUser().isEmpty()) {
                    String fhirUserRef = user.getFhirUser();
                    context.getClaims().claim("fhirUser", fhirUserRef);
                    logger.debug("🎫 [TOKEN-CUSTOMIZER] Added fhirUser claim: {}", fhirUserRef);
                    
                    // ONLY add patient claim from fhirUser if no patient was selected via picker
                    // (i.e., this is a patient standalone launch, not provider launch)
                    if (selectedPatientId == null && fhirUserRef.startsWith("Patient/")) {
                        String patientId = fhirUserRef.substring(8); // Extract "example" from "Patient/example"
                        context.getClaims().claim("patient", patientId);
                        logger.debug("🎫 [TOKEN-CUSTOMIZER] Added patient claim '{}' from fhirUser (patient standalone launch)", patientId);
                    }
                }
            }
        };
    }

    /**
     * JWT decoder for validating access tokens
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }
    
    /**
     * JwtEncoder for issuing custom application tokens (e.g., admin login) signed with same JWK.
     */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * JWK Source for signing and validating JWTs
     * Uses the pre-initialized signing key from @PostConstruct
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        return (jwkSelector, securityContext) -> {
            if (signingKey == null) {
                throw new IllegalStateException("OAuth signing key not initialized");
            }
            JWKSet jwkSet = new JWKSet(signingKey);
            return jwkSelector.select(jwkSet);
        };
    }
    
    /**
     * Generate a new RSA key pair for JWT signing
     */
    private RSAKey generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    /**
     * Get the config collection from Couchbase
     * Returns null if collection doesn't exist yet (during initialization)
     * FAST-FAIL: Checks collection existence before attempting KV operations
     */
    private boolean isConfigCollectionAvailable() {
        Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
        if (cluster == null) {
            logger.debug("No active Couchbase connection for '{}'", DEFAULT_CONNECTION);
            return false;
        }
        boolean bucketOk = false, scopeOk = false, collectionOk = false;
        try {
            String bucketCheckSql = "SELECT RAW name FROM system:buckets WHERE `name` = $name LIMIT 1";
            var result = cluster.query(bucketCheckSql, com.couchbase.client.java.query.QueryOptions.queryOptions().parameters(JsonObject.create().put("name", BUCKET_NAME)));
            bucketOk = !result.rowsAs(String.class).isEmpty();
            if (!bucketOk) logger.debug("Bucket '{}' NOT found (system:buckets)", BUCKET_NAME);
        } catch (Exception e) {
            logger.debug("Bucket existence check error: {}", e.getMessage());
        }
        try {
            if (bucketOk) {
                String scopeSql = "SELECT RAW name FROM system:scopes WHERE `bucket` = $bucket AND `name` = $scope LIMIT 1";
                var result = cluster.query(scopeSql, com.couchbase.client.java.query.QueryOptions.queryOptions().parameters(JsonObject.create().put("bucket", BUCKET_NAME).put("scope", SCOPE_NAME)));
                scopeOk = !result.rowsAs(String.class).isEmpty();
                if (!scopeOk) logger.debug("Scope '{}.{}' NOT found (system:scopes)", BUCKET_NAME, SCOPE_NAME);
            }
        } catch (Exception e) {
            logger.debug("Scope existence check error: {}", e.getMessage());
        }
        try {
            if (bucketOk && scopeOk) {
                String collSql = "SELECT RAW name FROM system:keyspaces WHERE `bucket` = $bucket AND `scope` = $scope AND `name` = $name LIMIT 1";
                var result = cluster.query(collSql, com.couchbase.client.java.query.QueryOptions.queryOptions().parameters(JsonObject.create().put("bucket", BUCKET_NAME).put("scope", SCOPE_NAME).put("name", COLLECTION_NAME)));
                collectionOk = !result.rowsAs(String.class).isEmpty();
                if (!collectionOk) logger.debug("Collection '{}.{}.{}' NOT found (system:keyspaces)", BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME);
            }
        } catch (Exception e) {
            logger.debug("Collection existence check error: {}", e.getMessage());
        }
        if (!(bucketOk && scopeOk && collectionOk)) {
            logger.warn("OAuth JWK collection unavailable (bucketOk={} scopeOk={} collectionOk={})", bucketOk, scopeOk, collectionOk);
        }
        return bucketOk && scopeOk && collectionOk;
    }

    /**
     * Load existing RSA JWK from Couchbase
     * Returns null if key doesn't exist (will generate new one)
     * Stored in: fhir.Admin.config collection with document ID "oauth-signing-key"
     */
    private RSAKey loadKeyFromCouchbase() {
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                logger.debug("No active Couchbase connection");
                return null;
            }
             // N1QL fetch (avoids KV timeout). Use VALUE to get raw doc object.
             // IMPORTANT: Previous query used SELECT VALUE d without alias; resulted in null row. Corrected to use RAW with alias.
             String docSql = String.format("SELECT RAW d FROM `%s`.`%s`.`%s` AS d USE KEYS '%s'", BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME, JWK_DOCUMENT_ID);
             long start = System.nanoTime();
             logger.debug("🔍 [JWK-LOAD] Executing signing key query: {}", docSql);
             var queryResult = cluster.query(docSql);
             var docs = queryResult.rowsAs(JsonObject.class);
             long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
             logger.debug("🔍 [JWK-LOAD] Query completed in {} ms; rowCount={}", elapsedMs, docs.size());

            if (docs.isEmpty()) {
                logger.debug("OAuth signing key document not found in fhir.Admin.config (bucket not initialized yet)");
                return null;
            }
            JsonObject doc = docs.get(0);
            if (doc == null) {
                // Fallback: attempt alternate projection without alias to see if document loads
                String altSql = String.format("SELECT RAW `%s`.`%s`.`%s` USE KEYS '%s'", BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME, JWK_DOCUMENT_ID);
                logger.warn("[JWK-LOAD] Null row returned; attempting alternate query: {}", altSql);
                var altResult = cluster.query(altSql);
                var altDocs = altResult.rowsAs(JsonObject.class);
                if (!altDocs.isEmpty() && altDocs.get(0) != null) {
                    doc = altDocs.get(0);
                    logger.debug("[JWK-LOAD] Alternate query succeeded; continuing with loaded document.");
                } else {
                    var rawRows = queryResult.rowsAs(String.class);
                    logger.warn("❌ First row still null after alternate query. originalRawRowsSize={} originalRawRows={} altRowsSize={} altRows={}", rawRows.size(), rawRows, altDocs.size(), altResult.rowsAs(String.class));
                    throw new IllegalStateException("Signing key query returned null row (both primary and alternate forms)");
                }
            }
             // Log truncated doc for diagnostics (avoid dumping private key material fully)
             logger.debug("🔍 [JWK-LOAD] Retrieved doc keys={}", doc.getNames());
             
             // Try new format first (jwkSetString) - stores JWK as raw string to preserve private key
             String jwkSetJson = doc.getString("jwkSetString");
             if (jwkSetJson == null) {
                 // Fallback to old format (jwkSet as JsonObject) for backwards compatibility
                 logger.warn("⚠️ [JWK-LOAD] No 'jwkSetString' field, trying legacy 'jwkSet' field...");
                 JsonObject jwkSetObj = doc.getObject("jwkSet");
                 if (jwkSetObj == null) {
                     logger.error("❌ [JWK-LOAD] Document has neither 'jwkSetString' nor 'jwkSet' field. Document structure: {}", doc.getNames());
                     throw new IllegalStateException("JWK document exists but has no jwkSet");
                 }
                 jwkSetJson = jwkSetObj.toString();
                 logger.warn("⚠️ [JWK-LOAD] Using legacy 'jwkSet' field - private key may be missing!");
             }
             logger.debug("🔍 [JWK-LOAD] jwkSet found, parsing JWKSet JSON...");
             logger.debug("🔍 [JWK-LOAD] jwkSet JSON length: {} chars", jwkSetJson.length());
             
             JWKSet set = JWKSet.parse(jwkSetJson);
             logger.debug("🔍 [JWK-LOAD] Parsed JWKSet with {} keys", set.getKeys().size());
             
             RSAKey loaded = (RSAKey) set.getKeys().get(0);
             logger.debug("🔍 [JWK-LOAD] First key: kty={}, kid={}, hasPrivateKey={}", 
                 loaded.getKeyType(), loaded.getKeyID(), loaded.isPrivate());
             
             if (!loaded.isPrivate()) {
                 logger.error("❌ [JWK-LOAD] RSA key lacks private key! This means the JWKSet was exported without private parts.");
                 logger.error("❌ [JWK-LOAD] Key details: algorithm={}, keyUse={}, keyOperations={}", 
                     loaded.getAlgorithm(), loaded.getKeyUse(), loaded.getKeyOperations());
                 throw new IllegalStateException("JWK in Couchbase lacks private key - was it exported without private parts?");
             }
             return loaded;
        } catch (Exception e) {
            logger.warn("Failed to load OAuth signing key from Couchbase: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Authorization Server settings (endpoint URLs)
     * Uses base URL from config.yaml (loaded as system property by ConfigurationStartupService)
     * @DependsOn ensures config.yaml is loaded before this bean is created
     */
    @Bean
    @org.springframework.context.annotation.DependsOn("configurationStartupService")
    public AuthorizationServerSettings authorizationServerSettings() {
        // Get issuer from system property (set by config.yaml) or fall back to environment variable
        String configBaseUrl = System.getProperty("app.baseUrl");
        if (configBaseUrl == null || configBaseUrl.isEmpty()) {
            configBaseUrl = System.getenv("APP_BASE_URL");
        }
        if (configBaseUrl == null || configBaseUrl.isEmpty()) {
            configBaseUrl = "http://localhost:8080/fhir"; // Last resort fallback
            logger.warn("⚠️  No app.baseUrl found in system properties or environment, using fallback: {}", configBaseUrl);
        }
        
        // Extract issuer by removing the /fhir path while preserving the full host:port
        String issuer = configBaseUrl;
        if (issuer.endsWith("/fhir")) {
            issuer = issuer.substring(0, issuer.length() - 5); // Remove last 5 chars: "/fhir"
        }
        
        logger.debug("🔐 OAuth Authorization Server issuer: {}", issuer);
        
        return AuthorizationServerSettings.builder()
                .issuer(issuer) // Uses base URL from config.yaml
                .authorizationEndpoint("/oauth2/authorize")
                .tokenEndpoint("/oauth2/token")
                .tokenIntrospectionEndpoint("/oauth2/introspect")
                .tokenRevocationEndpoint("/oauth2/revoke")
                .jwkSetEndpoint("/oauth2/jwks")
                .oidcUserInfoEndpoint("/oauth2/userinfo")
                .build();
    }
}

