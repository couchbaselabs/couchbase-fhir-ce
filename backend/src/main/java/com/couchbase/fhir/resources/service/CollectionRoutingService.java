package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.rest.param.DateParam;
import com.couchbase.common.config.FhirResourceMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Service for routing FHIR resource operations to the correct collections
 * based on the resource mapping configuration.
 * 
 * This service acts as a bridge between the existing DAO layer and the new
 * collection mapping system, ensuring all CRUD operations use the correct
 * target collections.
 */
@Service
public class CollectionRoutingService {
    
    private static final Logger logger = LoggerFactory.getLogger(CollectionRoutingService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    
    @Autowired
    private FhirResourceMappingService mappingService;
    
    /**
     * Get the target collection for a FHIR resource type
     * @param resourceType The FHIR resource type (e.g., "Patient", "Organization")
     * @return The collection name where this resource should be stored
     * @throws IllegalArgumentException if the resource type is not supported
     */
    public String getTargetCollection(String resourceType) {
        Optional<String> collection = mappingService.getTargetCollection(resourceType);
        if (collection.isPresent()) {
            logger.debug("Routing {} to collection: {}", resourceType, collection.get());
            return collection.get();
        } else {
            String errorMsg = String.format("Unsupported FHIR resource type: %s. Supported types: %s", 
                resourceType, mappingService.getSupportedResourceTypes());
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }
    
    /**
     * Get the full scope.collection path for a resource type
     * @param resourceType The FHIR resource type
     * @return The full path (scope.collection) for this resource type
     * @throws IllegalArgumentException if the resource type is not supported
     */
    public String getFullCollectionPath(String resourceType) {
        String collection = getTargetCollection(resourceType);
        return DEFAULT_SCOPE + "." + collection;
    }
    
    /**
     * Get the FTS index for a FHIR resource type
     * @param resourceType The FHIR resource type
     * @return The FTS index name for this resource type, or null if not found
     */
    public String getFtsIndex(String resourceType) {
        Optional<String> ftsIndex = mappingService.getFtsIndex(resourceType);
        if (ftsIndex.isPresent()) {
            // Get the bucket name from the tenant context
            String bucketName = com.couchbase.fhir.resources.config.TenantContextHolder.getTenantId();
            String fullyQualifiedIndex = bucketName + "." + DEFAULT_SCOPE + "." + ftsIndex.get();
            logger.debug("Using FTS index {} for resource type {}", fullyQualifiedIndex, resourceType);
            return fullyQualifiedIndex;
        } else {
            logger.warn("No FTS index found for resource type: {}", resourceType);
            return null;
        }
    }
    
    /**
     * Validate that a resource type is supported
     * @param resourceType The FHIR resource type to validate
     * @return true if the resource type is supported
     */
    public boolean isResourceSupported(String resourceType) {
        return mappingService.isResourceSupported(resourceType);
    }
    
    /**
     * Build a N1QL query that targets the correct collection for a resource type
     * @param resourceType The FHIR resource type
     * @param baseQuery The base N1QL query (without collection specification)
     * @return The query with the correct collection path
     * @throws IllegalArgumentException if the resource type is not supported
     */
    public String buildCollectionQuery(String resourceType, String baseQuery) {
        String collection = getTargetCollection(resourceType);
        // Replace the old pattern `Resources.resourceType` with `Resources.collection`
        return baseQuery.replace(
            DEFAULT_SCOPE + "." + resourceType, 
            DEFAULT_SCOPE + "." + collection
        );
    }
    
    /**
     * Build a N1QL query for reading a specific resource from the correct collection
     * @param bucketName The bucket name
     * @param resourceType The FHIR resource type
     * @param documentKey The document key
     * @return The N1QL query string
     */
    public String buildReadQuery(String bucketName, String resourceType, String documentKey) {
        String collection = getTargetCollection(resourceType);
        return String.format(
            "SELECT c.* " +
            "FROM `%s`.`%s`.`%s` c " +
            "USE KEYS '%s'",
            bucketName, DEFAULT_SCOPE, collection, documentKey
        );
    }
    
    /**
     * Build a N1QL query for reading multiple resources from the correct collection
     * @param bucketName The bucket name
     * @param resourceType The FHIR resource type
     * @param documentKeys The list of document keys
     * @return The N1QL query string
     */
    public String buildReadMultipleQuery(String bucketName, String resourceType, List<String> documentKeys) {
        String collection = getTargetCollection(resourceType);
        String keysArray = "[" + String.join(", ", documentKeys) + "]";
        return String.format(
            "SELECT c.* " +
            "FROM `%s`.`%s`.`%s` c " +
            "USE KEYS %s",
            bucketName, DEFAULT_SCOPE, collection, keysArray
        );
    }
    
    /**
     * Build a N1QL query for inserting a resource into the correct collection
     * @param bucketName The bucket name
     * @param resourceType The FHIR resource type
     * @return The N1QL INSERT query string
     */
    public String buildInsertQuery(String bucketName, String resourceType) {
        String collection = getTargetCollection(resourceType);
        return String.format(
            "INSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ($key, $value)",
            bucketName, DEFAULT_SCOPE, collection
        );
    }
    
    /**
     * Build a N1QL query for updating a resource in the correct collection
     * @param bucketName The bucket name
     * @param resourceType The FHIR resource type
     * @return The N1QL UPSERT query string
     */
    public String buildUpsertQuery(String bucketName, String resourceType) {
        String collection = getTargetCollection(resourceType);
        return String.format(
            "UPSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ($key, $value)",
            bucketName, DEFAULT_SCOPE, collection
        );
    }
    
    /**
     * Build a N1QL query for deleting a resource from the correct collection
     * @param bucketName The bucket name
     * @param resourceType The FHIR resource type
     * @param documentKey The document key
     * @return The N1QL DELETE query string
     */
    public String buildDeleteQuery(String bucketName, String resourceType, String documentKey) {
        String collection = getTargetCollection(resourceType);
        return String.format(
            "DELETE FROM `%s`.`%s`.`%s` USE KEYS '%s'",
            bucketName, DEFAULT_SCOPE, collection, documentKey
        );
    }
    
    /**
     * Get routing statistics for monitoring/debugging
     * @return String containing routing statistics
     */
    public String getRoutingStatistics() {
        return String.format(
            "Collection Routing Statistics:\n" +
            "  - Total resource types: %d\n" +
            "  - Total collections: %d\n" +
            "  - Default scope: %s\n" +
            "  - Supported resource types: %s",
            mappingService.getSupportedResourceTypes().size(),
            mappingService.getCollectionNames().size(),
            DEFAULT_SCOPE,
            mappingService.getSupportedResourceTypes()
        );
    }

    public String buildHistoryQuery(String bucketName, String resourceType, String id , DateParam since) {
        String collection = "Versions"; // all history entries stored in version collection
        StringBuilder query = new StringBuilder();
        query.append("SELECT v.* ")
                .append("FROM `").append(bucketName).append("`.`").append(DEFAULT_SCOPE).append("`.`").append(collection).append("` v ")
                .append("WHERE v.resourceType = '").append(resourceType).append("' ")
                .append("AND v.id = '").append(id).append("' ");

        if (since != null && since.getValue() != null) {
            Instant sinceInstant = since.getValue().toInstant();
            String isoSince = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(sinceInstant);
            query.append("AND v.meta.lastUpdated >= '").append(isoSince).append("' ");
        }

        query.append("ORDER BY v.meta.lastUpdated ASC");
        return query.toString();
    }

    public String buildReadVersionQuery(String bucketName, String resourceType,String id , String versionId) {
        String collection = "Versions";
        String documentKey = resourceType +"/"+ id + "/" + versionId;

        return String.format(
                "SELECT c.* " +
                        "FROM `%s`.`%s`.`%s` c " +
                        "USE KEYS '%s'",
                bucketName, DEFAULT_SCOPE, collection, documentKey
        );
    }

}
