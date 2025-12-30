package com.couchbase.admin.fhirBucket.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.fhirBucket.config.FhirBucketProperties;
import com.couchbase.admin.fhirBucket.model.FhirBucketConfig;
import com.couchbase.admin.fhirBucket.model.FhirConversionRequest;
import com.couchbase.admin.fhirBucket.model.FhirConversionResponse;
import com.couchbase.admin.fhirBucket.model.FhirConversionStatus;
import com.couchbase.admin.fhirBucket.model.FhirConversionStatusDetail;
import com.couchbase.admin.fts.config.FtsIndexCreator;
import com.couchbase.admin.gsi.service.GsiIndexService;
import com.couchbase.admin.tokens.service.JwtTokenCacheService;
import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpPath;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.manager.collection.CollectionManager;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.common.config.AdminConfig;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FhirBucketService {

    private static final Logger logger = LoggerFactory.getLogger(FhirBucketService.class);

    @Autowired private ConnectionService connectionService;
    @Autowired private FhirBucketConfigService fhirBucketConfigService;
    @Autowired private FhirBucketValidator fhirBucketValidator;
    @Autowired private FhirBucketProperties fhirProperties;
    @Autowired private FtsIndexCreator ftsIndexCreator;
    @Autowired private GsiIndexService gsiIndexService;
    @Autowired private UserService userService;
    @Autowired private AdminConfig adminConfig;
    @Autowired(required = false) private com.couchbase.fhir.auth.AuthorizationServerConfig authorizationServerConfig;
    @Autowired private JwtTokenCacheService jwtTokenCacheService;

    private final Map<String, FhirConversionStatusDetail> operationStatus = new ConcurrentHashMap<>();

    /** Start FHIR bucket conversion process with custom configuration. */
    public FhirConversionResponse startConversion(String bucketName, String connectionName, FhirConversionRequest request) {
        String operationId = UUID.randomUUID().toString();
        FhirConversionStatusDetail statusDetail = new FhirConversionStatusDetail(operationId, bucketName);
        operationStatus.put(operationId, statusDetail);
        CompletableFuture.runAsync(() -> performConversion(operationId, bucketName, connectionName, request));
        return new FhirConversionResponse(operationId, bucketName, FhirConversionStatus.INITIATED, "FHIR bucket conversion started");
    }

    /** Start FHIR bucket conversion process with default configuration. */
    public FhirConversionResponse startConversion(String bucketName, String connectionName) {
        return startConversion(bucketName, connectionName, null);
    }

    /** Get conversion status. */
    public FhirConversionStatusDetail getConversionStatus(String operationId) {
        return operationStatus.get(operationId);
    }

    /** Perform the actual conversion process with custom configuration. */
    private void performConversion(String operationId, String bucketName, String connectionName, FhirConversionRequest request) {
        FhirConversionStatusDetail status = operationStatus.get(operationId);
        try {
            status.setStatus(FhirConversionStatus.IN_PROGRESS);

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }

            CollectionManager collectionManager = cluster.bucket(bucketName).collections();

            updateStatus(status, "create_resources_scope", "Creating Resources scope");
            createScope(collectionManager, "Resources");
            status.setCompletedSteps(1);

            updateStatus(status, "create_resource_collections", "Creating Resource collections");
            FhirBucketProperties.ScopeConfiguration resourcesScope = getScope("resources");
            createCollections(collectionManager, "Resources", getCollectionNames(resourcesScope));
            status.setCompletedSteps(2);

            updateStatus(status, "create_indexes", "Creating primary indexes for collections");
            createIndexes(cluster, bucketName);
            status.setCompletedSteps(3);

            updateStatus(status, "build_deferred_indexes", "Building deferred indexes");
            buildDeferredIndexes(cluster, bucketName);
            status.setCompletedSteps(4);

            updateStatus(status, "create_fts_indexes", "Creating FTS indexes for collections");
            createFtsIndexes(connectionName, bucketName);
            status.setCompletedSteps(5);

            updateStatus(status, "create_gsi_indexes", "Creating GSI indexes from gsi-indexes.sql");
            createGsiIndexes(cluster, bucketName);
            status.setCompletedSteps(6);

            updateStatus(status, "mark_as_fhir", "Marking bucket as FHIR-enabled");
            markAsFhirBucketWithConfig(bucketName, connectionName, request);
            status.setCompletedSteps(7);

            updateStatus(status, "persist_oauth_key", "Persisting OAuth signing key");
            createOAuthSigningKey(cluster, bucketName);
            status.setCompletedSteps(8);

            logger.debug("Reloading JWT token cache after initialization...");
            try {
                jwtTokenCacheService.loadActiveTokens();
                if (jwtTokenCacheService.isInitialized()) {
                    int cacheSize = jwtTokenCacheService.getCacheSize();
                    logger.debug("Token cache reloaded with {} active tokens", cacheSize);
                } else {
                    logger.debug("Token cache initialized (no tokens yet)");
                }
            } catch (Exception e) {
                logger.warn("Failed to reload token cache after initialization: {}", e.getMessage());
            }

            status.setStatus(FhirConversionStatus.COMPLETED);
            status.setCurrentStepDescription("FHIR bucket conversion completed successfully");
        } catch (Exception e) {
            status.setStatus(FhirConversionStatus.FAILED);
            status.setErrorMessage(e.getMessage());
            status.setCurrentStepDescription("Conversion failed: " + e.getMessage());
        }
    }

    /** Ensure the Admin scope and required Admin collections exist. */
    public void ensureAdminCollections(String connectionName, String bucketName) {
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            logger.warn("No active connection found for: {}", connectionName);
            return;
        }

        try {
            CollectionManager collectionManager = cluster.bucket(bucketName).collections();
            createScope(collectionManager, "Admin");
            createCollections(collectionManager, "Admin", getAdminCollections());
        } catch (Exception e) {
            logger.warn("Failed to ensure Admin collections: {}", e.getMessage());
            logger.debug("Admin collection ensure error", e);
        }
    }

    /** Check whether Admin scope and required collections are already present. */
    public boolean areAdminCollectionsPresent(String connectionName, String bucketName) {
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            return false;
        }

        try {
            CollectionManager collectionManager = cluster.bucket(bucketName).collections();
            var scopes = collectionManager.getAllScopes();
            var adminScopeOpt = scopes.stream().filter(scope -> "Admin".equals(scope.name())).findFirst();
            if (adminScopeOpt.isEmpty()) {
                return false;
            }

            Set<String> expected = new HashSet<>(getAdminCollections());
            Set<String> existing = adminScopeOpt.get().collections().stream()
                .map(CollectionSpec::name)
                .collect(Collectors.toSet());

            return existing.containsAll(expected);
        } catch (Exception e) {
            logger.debug("Could not check Admin collections presence: {}", e.getMessage());
            return false;
        }
    }

    private FhirBucketProperties.ScopeConfiguration getScope(String name) {
        Map<String, FhirBucketProperties.ScopeConfiguration> scopes = fhirProperties.getScopes();
        return scopes != null ? scopes.get(name) : null;
    }

    private List<String> getCollectionNames(FhirBucketProperties.ScopeConfiguration scopeConfig) {
        if (scopeConfig == null || scopeConfig.getCollections() == null) {
            return List.of();
        }
        return scopeConfig.getCollections().stream()
            .map(FhirBucketProperties.CollectionConfiguration::getName)
            .collect(Collectors.toList());
    }

    private List<String> getAdminCollections() {
        List<String> configured = getCollectionNames(getScope("admin"));
        if (!configured.isEmpty()) {
            return configured;
        }
        return Arrays.asList("config", "users", "tokens", "clients", "cache", "bulk_groups");
    }

    private void createInitialAdminUserIfNeeded() {
        try {
            String email = adminConfig.getEmail();
            String password = adminConfig.getPassword();
            String name = adminConfig.getName();

            if (email == null || email.isEmpty()) {
                logger.warn("Skipping initial Admin user creation: admin.email is not configured");
                return;
            }

            if (userService.getUserById(email).isPresent()) {
                logger.debug("Initial Admin user '{}' already exists - skipping creation", email);
                return;
            }

            User adminUser = new User();
            adminUser.setId(email);
            adminUser.setUsername(name != null ? name : "Administrator");
            adminUser.setEmail(email);
            adminUser.setRole("admin");
            adminUser.setAuthMethod("local");
            adminUser.setPasswordHash(password);

            userService.createUser(adminUser, "system");
            logger.debug("Initial Admin user '{}' created successfully in Admin.users collection", email);
        } catch (Exception e) {
            logger.error("Failed to create initial Admin user from config.yaml: {}", e.getMessage());
        }
    }

    private void updateStatus(FhirConversionStatusDetail status, String stepName, String description) {
        status.setCurrentStep(stepName);
        status.setCurrentStepDescription(description);
    }

    private void createScope(CollectionManager manager, String scopeName) throws Exception {
        try {
            manager.createScope(scopeName);
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    private void createCollections(CollectionManager manager, String scopeName, List<String> collections) throws Exception {
        if (collections == null || collections.isEmpty()) return;
        for (String coll : collections) {
            try {
                manager.createCollection(CollectionSpec.create(coll, scopeName));
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("already exists")) {
                    throw e;
                }
            }
        }
    }

    private void createIndexes(Cluster cluster, String bucketName) throws Exception {
        FhirBucketProperties.ScopeConfiguration resourcesScope = getScope("resources");
        if (resourcesScope == null || resourcesScope.getCollections() == null) {
            return;
        }

        for (FhirBucketProperties.CollectionConfiguration collection : resourcesScope.getCollections()) {
            if (collection.getIndexes() == null || collection.getIndexes().isEmpty()) {
                continue;
            }
            for (FhirBucketProperties.IndexConfiguration index : collection.getIndexes()) {
                if (index.getSql() == null) {
                    continue;
                }
                try {
                    String sql = index.getSql().replace("{bucket}", bucketName);
                    cluster.query(sql, QueryOptions.queryOptions().timeout(Duration.ofMinutes(5)));
                } catch (Exception e) {
                    if (e.getMessage() == null || !e.getMessage().contains("already exists")) {
                        throw e;
                    }
                }
            }
        }
    }

    private void buildDeferredIndexes(Cluster cluster, String bucketName) throws Exception {
        List<FhirBucketProperties.BuildCommand> buildCommands = fhirProperties.getBuildCommands();
        if (buildCommands == null || buildCommands.isEmpty()) {
            return;
        }
        for (FhirBucketProperties.BuildCommand buildCommand : buildCommands) {
            String query = buildCommand.getQuery().replace("{bucket}", bucketName);
            var result = cluster.query(query);
            for (String buildIndexSql : result.rowsAs(String.class)) {
                buildIndexSql = buildIndexSql.replaceAll("^\"|\"$", "");
                try {
                    cluster.query(buildIndexSql);
                } catch (Exception e) {
                    logger.error("Failed to build index: {} - {}", buildIndexSql, e.getMessage());
                }
            }
        }
    }

    private void createFtsIndexes(String connectionName, String bucketName) throws Exception {
        try {
            ftsIndexCreator.createAllFtsIndexesForBucket(connectionName, bucketName);
        } catch (Exception e) {
            logger.error("Failed to create FTS indexes for bucket: {}", bucketName, e);
            logger.warn("Continuing bucket creation without FTS indexes");
        }
    }

    private void createGsiIndexes(Cluster cluster, String bucketName) throws Exception {
        try {
            gsiIndexService.createGsiIndexes(cluster, bucketName);
        } catch (Exception e) {
            logger.error("Failed to create GSI indexes for bucket: {}", bucketName, e);
            logger.warn("Continuing bucket creation - some GSI indexes may be missing");
        }
    }

    private void markAsFhirBucketWithConfig(String bucketName, String connectionName, FhirConversionRequest request) throws Exception {
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
        }

        FhirBucketConfig customConfig = request != null ? request.getFhirConfiguration() : null;

        JsonArray profileConfig = JsonArray.create();
        if (customConfig != null && customConfig.getProfiles() != null && !customConfig.getProfiles().isEmpty()) {
            for (FhirBucketConfig.Profile profile : customConfig.getProfiles()) {
                profileConfig.add(JsonObject.create()
                    .put("profile", profile.getProfile() != null ? profile.getProfile() : "US Core")
                    .put("version", profile.getVersion() != null ? profile.getVersion() : "6.1.0"));
            }
        } else {
            profileConfig.add(JsonObject.create().put("profile", "US Core").put("version", "6.1.0"));
        }

        JsonObject validationConfig = JsonObject.create();
        if (customConfig != null && customConfig.getValidation() != null) {
            FhirBucketConfig.Validation validation = customConfig.getValidation();
            validationConfig
                .put("mode", validation.getMode() != null ? validation.getMode() : "lenient")
                .put("profile", validation.getProfile() != null ? validation.getProfile() : "none");
        } else {
            validationConfig.put("mode", "lenient").put("profile", "none");
        }

        JsonObject logsConfig = JsonObject.create();
        if (customConfig != null && customConfig.getLogs() != null) {
            FhirBucketConfig.Logs logs = customConfig.getLogs();
            logsConfig
                .put("enableSystem", logs.isEnableSystem())
                .put("enableCRUDAudit", logs.isEnableCRUDAudit())
                .put("enableSearchAudit", logs.isEnableSearchAudit())
                .put("rotationBy", logs.getRotationBy() != null ? logs.getRotationBy() : "size")
                .put("number", logs.getNumber() > 0 ? logs.getNumber() : 30)
                .put("s3Endpoint", logs.getS3Endpoint() != null ? logs.getS3Endpoint() : "");
        } else {
            logsConfig
                .put("enableSystem", false)
                .put("enableCRUDAudit", false)
                .put("enableSearchAudit", false)
                .put("rotationBy", "size")
                .put("number", 30)
                .put("s3Endpoint", "");
        }

        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy 'at' hh:mm:ss a z");
        String createdAtFormatted = now.format(formatter);

        JsonObject fhirConfig = JsonObject.create()
            .put("isFHIR", true)
            .put("createdAt", createdAtFormatted)
            .put("version", "1")
            .put("description", "FHIR-enabled bucket configuration")
            .put("fhirRelease", customConfig != null && customConfig.getFhirRelease() != null ? customConfig.getFhirRelease() : "Release 4")
            .put("profiles", profileConfig)
            .put("validation", validationConfig)
            .put("logs", logsConfig);

        String documentId = "fhir-config";
        String sql = String.format("INSERT INTO `%s`.`Admin`.`config` (KEY, VALUE) VALUES ('%s', %s)", bucketName, documentId, fhirConfig.toString());
        try {
            cluster.query(sql);
            fhirBucketConfigService.clearConfigCache(bucketName, connectionName);
            fhirBucketValidator.clearCache(bucketName, connectionName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                fhirBucketConfigService.clearConfigCache(bucketName, connectionName);
                fhirBucketValidator.clearCache(bucketName, connectionName);
            } else {
                logger.error("Failed to mark bucket {} as FHIR-enabled: {}", bucketName, e.getMessage());
                throw e;
            }
        }
    }

    /** Get all FHIR-enabled buckets. */
    public List<String> getFhirBuckets(String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return new ArrayList<>();
            }

            String sql = "SELECT name FROM system:buckets WHERE namespace_id = 'default'";
            var result = cluster.query(sql);
            var buckets = new ArrayList<String>();

            for (var row : result.rowsAsObject()) {
                String bucket = row.getString("name");
                if (isFhirBucket(bucket, connectionName)) {
                    buckets.add(bucket);
                }
            }
            return buckets;
        } catch (Exception e) {
            logger.error("Failed to get FHIR buckets: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Check if a bucket is FHIR-enabled by looking for the configuration document via REST API. */
    public boolean isFhirBucket(String bucketName, String connectionName) {
        try {
            String hostname = connectionService.getHostname(connectionName);
            int port = connectionService.getPort(connectionName);
            var connectionDetails = connectionService.getConnectionDetails(connectionName);
            if (hostname == null || connectionDetails == null) {
                return false;
            }
            return checkFhirConfigViaRest(bucketName, connectionName);
        } catch (Exception e) {
            logger.error("Failed to check if bucket {} is FHIR-enabled: {}", bucketName, e.getMessage());
            return false;
        }
    }

    private boolean checkFhirConfigViaRest(String bucketName, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return false;
            }

            CouchbaseHttpClient httpClient = cluster.httpClient();
            HttpResponse httpResponse = httpClient.get(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/buckets/{}/scopes/Admin/collections/config/docs/fhir-config", bucketName)
            );

            int statusCode = httpResponse.statusCode();
            if (statusCode == 200) return true;
            if (statusCode == 404) return false;
            logger.error("Unexpected status code {} when checking FHIR config for bucket {}", statusCode, bucketName);
            return false;
        } catch (Exception e) {
            logger.error("REST check for FHIR config failed: {}", e.getMessage());
            return false;
        }
    }

    /** Persist the current in-memory OAuth signing key to fhir.Admin.config collection. */
    private void createOAuthSigningKey(Cluster cluster, String bucketName) {
        try {
            Collection configCollection = cluster.bucket(bucketName).scope("Admin").collection("config");
            try {
                configCollection.get("oauth-signing-key");
                logger.debug("OAuth signing key already exists in fhir.Admin.config");
                return;
            } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
                // proceed
            }

            if (authorizationServerConfig == null) {
                throw new IllegalStateException("AuthorizationServerConfig not available to persist OAuth signing key");
            }

            RSAKey rsaKey = authorizationServerConfig.getCurrentKey();
            if (rsaKey == null) {
                throw new IllegalStateException("No OAuth signing key available to persist");
            }

            String keyId = rsaKey.getKeyID();
            String jwkJson = rsaKey.toJSONString();
            String jwkSetJson = String.format("{\"keys\":[%s]}", jwkJson);

            JsonObject doc = JsonObject.create()
                .put("id", "oauth-signing-key")
                .put("type", "jwk")
                .put("jwkSetString", jwkSetJson)
                .put("createdAt", Instant.now().toString())
                .put("updatedAt", Instant.now().toString());

            configCollection.upsert("oauth-signing-key", doc);
            logger.debug("Persisted OAuth signing key to fhir.Admin.config (kid: {})", keyId);
            try {
                var savedDoc = configCollection.get("oauth-signing-key").contentAsObject();
                String savedJwkStr = savedDoc.getString("jwkSetString");
                JWKSet.parse(savedJwkStr);
            } catch (Exception e) {
                logger.warn("Could not verify saved key: {}", e.getMessage());
            }

            if (authorizationServerConfig != null) {
                authorizationServerConfig.invalidateKeyCache();
            }

        } catch (Exception e) {
            logger.error("Failed to create OAuth signing key: {}", e.getMessage(), e);
            logger.warn("OAuth tokens will use ephemeral key (won't survive restarts)");
        }
    }
}

