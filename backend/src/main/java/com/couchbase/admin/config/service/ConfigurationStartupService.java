package com.couchbase.admin.config.service;

import com.couchbase.admin.connections.model.ConnectionRequest;
import com.couchbase.admin.connections.model.ConnectionResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.initialization.model.InitializationStatus;
import com.couchbase.admin.initialization.service.InitializationService;
import com.couchbase.admin.tokens.service.JwtTokenCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;

/**
 * Service that automatically loads configuration and establishes connections on application startup
 */
@Service
public class ConfigurationStartupService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationStartupService.class);
    private static final String DEFAULT_CONFIG_FILE = "../config.yaml"; // relative to backend working dir (/app)
    private static final String CONFIG_SYS_PROP = "fhir.config";        // -Dfhir.config=/path/to/config.yaml
    private static final String CONFIG_ENV_VAR = "FHIR_CONFIG_FILE";    // environment override
    private static final String DEFAULT_CONNECTION_NAME = "default";

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private InitializationService initializationService;
    
    @Autowired
    private JwtTokenCacheService jwtTokenCacheService;

    @Autowired
    private com.couchbase.admin.fhirBucket.service.FhirBucketService fhirBucketService;
    
    @Autowired(required = false)
    private com.couchbase.fhir.auth.AuthorizationServerConfig authorizationServerConfig;

    @Autowired
    private com.couchbase.common.config.AdminConfig adminConfig;

    @Autowired
    private com.couchbase.admin.users.service.UserService userService;

    @Value("${app.security.use-keycloak:false}")
    private boolean useKeycloak;

    /**
     * Load configuration and establish connection after application is fully started
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("🔧 Application ready - attempting to load startup configuration");
        
        try {
            loadConfigurationAndConnect();
        } catch (Exception e) {
            logger.warn("⚠️ Failed to load startup configuration: {}", e.getMessage());
            logger.info("📋 FHIR Server will start without auto-connection - use frontend or REST API to establish connection");
        }
    }

    /**
     * Expose a retry method for external callers (e.g., REST) to trigger auto-connect again.
     * Returns true if execution attempted (config existed and autoConnect not explicitly false).
     */
    public boolean retryLoadConfigurationAndConnect() {
        try {
            return loadConfigurationAndConnect();
        } catch (Exception e) {
            logger.warn("⚠️ Retry auto-connect failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Load config.yaml and establish connection
     * @return true if an attempt to connect was made (config exists and not disabled)
     */
    public boolean loadConfigurationAndConnect() {
        // Resolve configuration file path with precedence:
        // 1. System property -Dfhir.config
        // 2. Env var FHIR_CONFIG_FILE
        // 3. Default relative ../config.yaml
        String overrideSys = System.getProperty(CONFIG_SYS_PROP);
        String overrideEnv = System.getenv(CONFIG_ENV_VAR);
        String chosenPath = overrideSys != null ? overrideSys : (overrideEnv != null ? overrideEnv : DEFAULT_CONFIG_FILE);

        Path configFile = Paths.get(chosenPath);
        if (!configFile.isAbsolute()) {
            configFile = Paths.get(System.getProperty("user.dir")).resolve(configFile).normalize();
        }

        if (overrideSys != null) {
            logger.info("🔧 Using config override via system property ({}): {}", CONFIG_SYS_PROP, configFile);
        } else if (overrideEnv != null) {
            logger.info("🔧 Using config override via environment variable ({}): {}", CONFIG_ENV_VAR, configFile);
        } else {
            logger.info("🔧 Using default config path: {}", configFile);
        }

        if (!Files.exists(configFile)) {
            logger.info("📋 No config.yaml found - FHIR Server will start without auto-connection");
            return false;
        }

        // Load and parse YAML
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData;
        
        try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
            yamlData = yaml.load(inputStream);
            
            if (yamlData == null) {
                logger.warn("⚠️ config.yaml is empty or invalid");
                return false;
            }
            
            logger.info("✅ Successfully loaded config.yaml with {} top-level keys", yamlData.size());
            
        } catch (Exception e) {
            logger.error("❌ Failed to read config.yaml: {}", e.getMessage());
            return false;
        }

        // Apply logging levels: logging.default + logging.overrides + direct entries
        @SuppressWarnings("unchecked")
        Map<String, Object> loggingSection = (Map<String, Object>) yamlData.get("logging");
        if (loggingSection != null) {
            Map<String, Object> levelsToApply = new HashMap<>();
            
            // Apply default level to root logger
            if (loggingSection.containsKey("default")) {
                String defaultLevel = String.valueOf(loggingSection.get("default"));
                levelsToApply.put("ROOT", defaultLevel);
            }
            
            // Apply overrides if present
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (Map<String, Object>) loggingSection.get("overrides");
            if (overrides != null && !overrides.isEmpty()) {
                levelsToApply.putAll(overrides);
            }
            
            // Apply direct logger entries (like ConfigurationStartupService: INFO)
            for (Map.Entry<String, Object> entry : loggingSection.entrySet()) {
                String key = entry.getKey();
                if (!key.equals("default") && !key.equals("overrides")) {
                    levelsToApply.put(key, entry.getValue());
                }
            }
            
            if (!levelsToApply.isEmpty()) {
                applyLoggingLevels(levelsToApply);
            }
        }

        // Extract Couchbase configuration: couchbase.connection and couchbase.sdk.overrides
        @SuppressWarnings("unchecked")
        Map<String, Object> couchbaseSection = (Map<String, Object>) yamlData.get("couchbase");
        
        if (couchbaseSection == null) {
            logger.warn("⚠️ No 'couchbase' section found in config.yaml");
            return false;
        }
        
        // Connection config: couchbase.connection
        @SuppressWarnings("unchecked")
        Map<String, Object> connectionConfig = (Map<String, Object>) couchbaseSection.get("connection");
        if (connectionConfig == null) {
            logger.warn("⚠️ No 'couchbase.connection' section found in config.yaml");
            return false;
        }
        
        // SDK configuration: couchbase.sdk.overrides
        Map<String, Object> sdkConfig = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> sdkSection = (Map<String, Object>) couchbaseSection.get("sdk");
        if (sdkSection != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (Map<String, Object>) sdkSection.get("overrides");
            if (overrides != null) {
                sdkConfig = overrides;
            }
        }
        
        // Apply SDK configuration (uses defaults if none specified)
        applyCouchbaseSdkConfiguration(sdkConfig);

        // Extract app configuration and determine autoConnect behavior.
        @SuppressWarnings("unchecked")
        Map<String, Object> appConfig = (Map<String, Object>) yamlData.get("app");
        boolean autoConnectExplicitFalse = appConfig != null && Boolean.FALSE.equals(appConfig.get("autoConnect"));
        boolean autoConnect = !autoConnectExplicitFalse; // default true when connection section present
        if (!autoConnect) {
            logger.info("📋 autoConnect explicitly disabled (app.autoConnect=false) - skipping auto-connection");
            return false;
        }
        
        // Extract app.baseUrl and set as system property for FhirServerConfig to use
        if (appConfig != null && appConfig.get("baseUrl") != null) {
            String baseUrl = String.valueOf(appConfig.get("baseUrl"));
            System.setProperty("app.baseUrl", baseUrl);
            logger.info("📋 FHIR Server base URL: {}", baseUrl);
        }

        // Extract admin credentials and set as system properties for AdminConfig to use
        @SuppressWarnings("unchecked")
        Map<String, Object> adminConfig = (Map<String, Object>) yamlData.get("admin");
        if (adminConfig != null) {
            if (adminConfig.get("email") != null) {
                System.setProperty("admin.email", String.valueOf(adminConfig.get("email")));
            }
            if (adminConfig.get("password") != null) {
                System.setProperty("admin.password", String.valueOf(adminConfig.get("password")));
            }
            if (adminConfig.get("name") != null) {
                System.setProperty("admin.name", String.valueOf(adminConfig.get("name")));
            }
            logger.info("🔐 Admin UI credentials loaded from config.yaml");
        }

        // Extract Keycloak configuration and set as system properties / environment variables
        @SuppressWarnings("unchecked")
        Map<String, Object> keycloakConfig = (Map<String, Object>) yamlData.get("keycloak");
        if (keycloakConfig != null && Boolean.TRUE.equals(keycloakConfig.get("enabled"))) {
            logger.info("🔐 Keycloak integration enabled in config.yaml");
            
            // Set USE_KEYCLOAK flag
            System.setProperty("USE_KEYCLOAK", "true");
            
            // Extract and set Keycloak properties
            if (keycloakConfig.get("url") != null) {
                String keycloakUrl = String.valueOf(keycloakConfig.get("url"));
                System.setProperty("KEYCLOAK_URL", keycloakUrl);
                logger.info("   🔗 Keycloak URL: {}", keycloakUrl);
            }
            
            if (keycloakConfig.get("realm") != null) {
                String realm = String.valueOf(keycloakConfig.get("realm"));
                System.setProperty("KEYCLOAK_REALM", realm);
                logger.info("   🏛️  Keycloak Realm: {}", realm);
            }
            
            if (keycloakConfig.get("adminUsername") != null) {
                System.setProperty("KEYCLOAK_ADMIN_USERNAME", String.valueOf(keycloakConfig.get("adminUsername")));
            }
            
            if (keycloakConfig.get("adminPassword") != null) {
                System.setProperty("KEYCLOAK_ADMIN_PASSWORD", String.valueOf(keycloakConfig.get("adminPassword")));
            }
            
            if (keycloakConfig.get("clientId") != null) {
                System.setProperty("KEYCLOAK_CLIENT_ID", String.valueOf(keycloakConfig.get("clientId")));
                logger.info("   🔑 Keycloak Client ID: {}", keycloakConfig.get("clientId"));
            }
            
            if (keycloakConfig.get("clientSecret") != null) {
                System.setProperty("KEYCLOAK_CLIENT_SECRET", String.valueOf(keycloakConfig.get("clientSecret")));
                logger.info("   🔑 Keycloak Client Secret: [REDACTED]");
            }
            
            // Construct JWKS URI from URL and realm
            String keycloakUrl = System.getProperty("KEYCLOAK_URL");
            String realm = System.getProperty("KEYCLOAK_REALM");
            if (keycloakUrl != null && realm != null) {
                String jwksUri = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
                System.setProperty("KEYCLOAK_JWKS_URI", jwksUri);
                logger.info("   🔐 Keycloak JWKS URI: {}", jwksUri);
            }
            
            // Set public URL if specified, otherwise derive from app.baseUrl
            if (keycloakConfig.get("publicUrl") != null) {
                System.setProperty("KEYCLOAK_PUBLIC_URL", String.valueOf(keycloakConfig.get("publicUrl")));
            } else if (appConfig != null && appConfig.get("baseUrl") != null) {
                // Default: use app baseUrl + /auth path
                String baseUrl = String.valueOf(appConfig.get("baseUrl"));
                String publicUrl = baseUrl.replaceFirst("/fhir$", "") + "/auth";
                System.setProperty("KEYCLOAK_PUBLIC_URL", publicUrl);
                logger.info("   🌐 Keycloak Public URL (derived): {}", publicUrl);
            }
            
            logger.info("✅ Keycloak configuration loaded from config.yaml");
        } else {
            // Keycloak not enabled or not present - ensure USE_KEYCLOAK is false
            System.setProperty("USE_KEYCLOAK", "false");
            logger.info("ℹ️  Keycloak integration disabled - using embedded Spring Authorization Server");
        }

        // CORS configuration is now in application.yml (not config.yaml)
        // See backend/src/main/resources/application.yml for CORS settings
        logger.info("ℹ️  CORS configuration is managed in application.yml (default: allow all origins)");

        // Create connection request from YAML data
        ConnectionRequest request = createConnectionRequest(connectionConfig);
        
        logger.info("🔗 Attempting auto-connection to: {} ({})", 
                   request.getConnectionString(), request.getServerType());

        // Establish connection
        ConnectionResponse response = connectionService.createConnection(request);
        
        if (response.isSuccess()) {
            logger.info("✅ Auto-connection successful!");
            
            // Initialize OAuth signing key after connection is established (only when embedded auth server is active)
            if (!useKeycloak && authorizationServerConfig != null) {
                initializeOAuthSigningKey();
            } else if (useKeycloak) {
                logger.info("🔐 Skipping embedded OAuth signing key initialization because Keycloak is enabled");
            } else {
                logger.info("🔐 No embedded AuthorizationServerConfig available; skipping signing key initialization");
            }
            
            // Initialize JWT token cache after connection is established
            initializeTokenCache();
            
            // Check initialization status for single-tenant "fhir" bucket
            checkAndReportInitializationStatus();

            // Ensure Admin collections exist (if missing) so admin seeding can proceed
            try {
                InitializationStatus status = initializationService.checkStatus(DEFAULT_CONNECTION_NAME);
                if (status != null && !status.isAdminInitialized()) {
                    logger.info("ℹ️ Admin collections not present - ensuring Admin scope/collections now");
                    fhirBucketService.ensureAdminCollections(DEFAULT_CONNECTION_NAME, initializationService.getFhirBucketName());
                }
            } catch (Exception e) {
                logger.debug("Could not ensure admin collections at startup: {}", e.getMessage());
            }

            // Attempt to seed initial Admin user (from config.yaml) when appropriate
            seedAdminUserIfNeeded();
            
        } else {
            String errorMsg = response.getMessage();
            logger.error("❌ Auto-connection failed: {}", errorMsg);
            if (errorMsg != null && (errorMsg.contains("authentication") || 
                                   errorMsg.contains("Authentication") ||
                                   errorMsg.contains("credentials") ||
                                   errorMsg.contains("password"))) {
                logger.error("🔐 AUTHENTICATION FAILURE DETECTED!");
                logger.error("📝 Please check your config.yaml file:");
                logger.error("   - Verify username and password are correct");
                logger.error("   - Ensure Couchbase server credentials match config.yaml");
                logger.error("   - Fix credentials and restart the application");
            } else {
                logger.info("📋 FHIR Server started but no connection established - use frontend or REST API to connect");
            }
        }
        return true;
    }

    /**
     * Check and report FHIR bucket initialization status
     * Single-tenant mode: expects exactly one bucket named "fhir"
     */
    private void checkAndReportInitializationStatus() {
        try {
            InitializationStatus status = initializationService.checkStatus(DEFAULT_CONNECTION_NAME);
            
            logger.info("╔══════════════════════════════════════════════════════════════╗");
            logger.info("║         FHIR SYSTEM INITIALIZATION STATUS                    ║");
            logger.info("╚══════════════════════════════════════════════════════════════╝");
            logger.info("📊 Status: {}", status.getStatus());
            logger.info("📦 Bucket: {}", status.getBucketName());
            logger.info("🔗 Connection: {}", status.isHasConnection() ? "✅ Connected" : "❌ Not Connected");
            logger.info("🪣 Bucket Exists: {}", status.isBucketExists() ? "✅ Yes" : "❌ No");
            logger.info("⚙️  FHIR Initialized: {}", status.isFhirInitialized() ? "✅ Yes" : "❌ No");
            logger.info("─────────────────────────────────────────────────────────────");
            
            switch (status.getStatus()) {
                case READY:
                    logger.info("✅ {}", status.getMessage());
                    logger.info("🚀 Backend startup complete - FHIR APIs are now available");
                    logger.info("💡 Collections will be warmed up automatically on first access");
                    break;
                    
                case BUCKET_MISSING:
                    logger.warn("⚠️  {}", status.getMessage());
                    logger.warn("📋 NEXT STEPS:");
                    logger.warn("   1. Create bucket '{}' in Couchbase UI or CLI", status.getBucketName());
                    logger.warn("   2. Use Admin UI to initialize FHIR configuration");
                    break;
                    
                case BUCKET_NOT_INITIALIZED:
                    logger.warn("⚠️  {}", status.getMessage());
                    logger.warn("📋 NEXT STEPS:");
                    logger.warn("   1. Open Admin UI in your browser");
                    logger.warn("   2. Click 'Initialize FHIR Bucket' to set up scopes/collections/indexes");
                    break;
                    
                case NOT_CONNECTED:
                    logger.error("❌ {}", status.getMessage());
                    break;
            }
            
            logger.info("╚══════════════════════════════════════════════════════════════╝");
            
        } catch (Exception e) {
            logger.error("❌ Failed to check initialization status: {}", e.getMessage());
        }
    }
    
    /**
     * Apply logging levels supplied in config.yaml under logging.levels
     */
    private void applyLoggingLevels(Map<String, Object> levels) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // Keep insertion order for deterministic logging of what changed
        Map<String, Object> ordered = new LinkedHashMap<>(levels);
        ordered.forEach((k, v) -> {
            if (v == null) return;
            String levelStr = v.toString().trim().toUpperCase();
            try {
                Level lvl = Level.valueOf(levelStr);
                context.getLogger(k).setLevel(lvl);
                logger.info("🪵 Logging level override: {} -> {}", k, lvl);
            } catch (IllegalArgumentException ex) {
                logger.warn("⚠️ Ignoring invalid log level '{}' for logger '{}'. Valid: TRACE, DEBUG, INFO, WARN, ERROR", levelStr, k);
            }
        });
    }

    /**
     * Create ConnectionRequest from YAML configuration
     */
    private ConnectionRequest createConnectionRequest(Map<String, Object> connectionConfig) {
        ConnectionRequest request = new ConnectionRequest();
        
        // Apply same localhost -> 127.0.0.1 transformation as frontend
        String connectionString = (String) connectionConfig.get("connectionString");
        String safeConnectionString = connectionString.replaceAll("localhost(?![\\w.])", "127.0.0.1");
        
        logger.debug("🔧 Connection string transformation: {} -> {}", connectionString, safeConnectionString);
        
        request.setName(DEFAULT_CONNECTION_NAME);
        request.setConnectionString(safeConnectionString);
        request.setUsername((String) connectionConfig.get("username"));
        request.setPassword((String) connectionConfig.get("password"));
        request.setServerType((String) connectionConfig.get("serverType"));
        request.setSslEnabled(Boolean.TRUE.equals(connectionConfig.get("sslEnabled")));
        
        // Set default bucket/scope/collection if not specified
        request.setBucket("fhir");        // Default FHIR bucket name
        request.setScope("Resources");    // Default FHIR scope name  
        request.setCollection("_default"); // Default collection name
        
        return request;
    }

    /**
     * Apply Couchbase SDK configuration from config.yaml to Spring Boot system properties
     * ONLY sets properties that are explicitly configured in config.yaml
     * All other parameters delegate to Couchbase Java SDK's built-in defaults
     */
    private void applyCouchbaseSdkConfiguration(Map<String, Object> sdkConfig) {
        if (sdkConfig.isEmpty()) {
            logger.info("🔧 Couchbase SDK configuration: Using SDK defaults (no overrides in config.yaml)");
            return;
        }
        
        logger.info("🔧 Couchbase SDK configuration overrides from config.yaml:");
        
        // Only set system properties for explicitly configured values
        sdkConfig.forEach((yamlKey, value) -> {
            String springProperty = "couchbase.sdk." + yamlKey;
            String stringValue = String.valueOf(value);
            System.setProperty(springProperty, stringValue);
            logger.info("   ✅ {}: {} (from config.yaml)", yamlKey, stringValue);
        });
        
        // Highlight critical transaction durability setting if configured
        String durability = System.getProperty("couchbase.sdk.transaction-durability");
        if (durability != null) {
            if ("NONE".equals(durability)) {
                logger.info("🔒 Transaction durability: {} (suitable for development/single-node)", durability);
            } else {
                logger.info("🔒 Transaction durability: {} (production setting - requires replicas)", durability);
            }
        }
        
        logger.info("ℹ️  Unconfigured SDK parameters will use Couchbase Java SDK defaults");
    }
    
    /**
     * Initialize the OAuth signing key after Couchbase connection is established
     * This must run AFTER the connection is established, before any login attempts
     */
    private void initializeOAuthSigningKey() {
        try {
            logger.info("🔐 Initializing OAuth signing key...");
            authorizationServerConfig.initializeSigningKey();
        } catch (Exception e) {
            logger.error("❌ Failed to initialize OAuth signing key: {}", e.getMessage());
            throw new IllegalStateException("Cannot start without OAuth signing key", e);
        }
    }
    
    /**
     * Initialize the JWT token cache after Couchbase connection is established
     */
    private void initializeTokenCache() {
        try {
            logger.info("🔐 Loading active JWT tokens into cache...");
            jwtTokenCacheService.loadActiveTokens();
            
            if (jwtTokenCacheService.isInitialized()) {
                int cacheSize = jwtTokenCacheService.getCacheSize();
                logger.info("✅ Token cache initialized with {} active tokens", cacheSize);
            } else {
                logger.warn("⏭️ Token cache not initialized (FHIR bucket may not be initialized yet)");
            }
            
        } catch (Exception e) {
            logger.warn("⚠️ Failed to initialize token cache: {}", e.getMessage());
            logger.debug("Token cache initialization error:", e);
            // Don't fail startup if cache initialization fails
        }
    }

    /**
     * Seed the initial Admin user defined in config.yaml (if configured)
     * - If Keycloak is enabled, attempts to create the user in Keycloak (delegated)
     * - Otherwise, seeds the local Couchbase users collection (requires FHIR initialization)
     */
    private void seedAdminUserIfNeeded() {
        try {
            // Only attempt seeding when Admin collections exist (we don't require full FHIR initialization).
            try {
                InitializationStatus status = initializationService.checkStatus(DEFAULT_CONNECTION_NAME);
                if (status == null || !status.isAdminInitialized()) {
                    logger.info("ℹ️  Skipping admin seeding: Admin collections not present (status={})", status == null ? "null" : status.getStatus());
                    return;
                }
            } catch (Exception e) {
                logger.warn("⚠️ Could not determine admin initialization status before seeding admin user: {}", e.getMessage());
                logger.debug("Initialization status check error:", e);
                // If we cannot determine status, fail-safe: skip seeding to avoid exceptions during startup
                return;
            }

            String email = adminConfig.getEmail();
            String password = adminConfig.getPassword();
            String name = adminConfig.getName();

            if (email == null || email.isEmpty()) {
                logger.info("ℹ️ No admin.email configured; skipping admin user seeding");
                return;
            }

            // If user already exists (either Keycloak or Couchbase), skip
            try {
                if (userService.getUserById(email).isPresent() || userService.getUserByEmail(email).isPresent()) {
                    logger.info("👤 Admin user '{}' already exists - skipping seeding", email);
                    return;
                }
            } catch (Exception e) {
                // getUserById/getUserByEmail may fail if DB not ready; continue and attempt create
                logger.debug("Could not check existing users (may be uninitialized): {}", e.getMessage());
            }

            com.couchbase.admin.users.model.User adminUser = new com.couchbase.admin.users.model.User();
            adminUser.setId(email);
            adminUser.setUsername(name != null && !name.isBlank() ? name : "Administrator");
            adminUser.setEmail(email);
            adminUser.setRole("admin");

            if (useKeycloak) {
                adminUser.setPasswordPlain(password);
                adminUser.setAuthMethod("keycloak");
            } else {
                // UserService.createUser will bcrypt the passwordHash passed here
                adminUser.setPasswordHash(password);
                adminUser.setAuthMethod("local");
            }

            userService.createUser(adminUser, "system");
            logger.info("✅ Seeded initial Admin user from config.yaml: {}", email);

        } catch (Exception e) {
            logger.warn("⚠️ Failed to seed initial Admin user: {}", e.getMessage());
            logger.debug("Admin seeding error:", e);
        }
    }
}