package com.couchbase.admin.users.bulkGroup.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service to execute FHIR search filters and return preview results.
 * NOW USES FtsGroupService for cleaner, modular architecture.
 * 
 * Supports multiple resource types for Group membership:
 * Device, Group, Medication, Patient, Practitioner, PractitionerRole, RelatedPerson, Substance
 */
@Service
public class FilterPreviewService {

    private static final Logger logger = LoggerFactory.getLogger(FilterPreviewService.class);
    private static final int MAX_PREVIEW_RESULTS = 10;

    // Resource types that can be group members (per FHIR spec)
    private static final Set<String> SUPPORTED_RESOURCE_TYPES = Set.of(
            "Device", "Group", "Medication", "Patient", "Practitioner",
            "PractitionerRole", "RelatedPerson", "Substance"
    );

    private final FtsGroupService ftsGroupService;
    private final ConnectionService connectionService;

    public FilterPreviewService(FtsGroupService ftsGroupService, ConnectionService connectionService) {
        this.ftsGroupService = ftsGroupService;
        this.connectionService = connectionService;
        logger.info("✅ FilterPreviewService initialized with FtsGroupService");
    }

    /**
     * Preview result containing total count and sample resources
     */
    public static class FilterPreviewResult {
        private final long totalCount;
        private final List<Map<String, Object>> sampleResources;
        private final String resourceType;
        private final String filter;

        public FilterPreviewResult(long totalCount, List<Map<String, Object>> sampleResources, 
                                   String resourceType, String filter) {
            this.totalCount = totalCount;
            this.sampleResources = sampleResources;
            this.resourceType = resourceType;
            this.filter = filter;
        }

        public long getTotalCount() { return totalCount; }
        public List<Map<String, Object>> getSampleResources() { return sampleResources; }
        public String getResourceType() { return resourceType; }
        public String getFilter() { return filter; }
    }

    /**
     * Execute a FHIR search filter and return preview (count + sample)
     * NOW USES FtsGroupService for modular, cleaner implementation.
     * 
     * @param resourceType The FHIR resource type (Patient, Practitioner, etc.)
     * @param filterQuery FHIR search parameters (e.g., "family=Smith&birthdate=ge1987-01-01")
     * @return FilterPreviewResult with total count and up to 10 sample resources
     */
    public FilterPreviewResult executeFilterPreview(String resourceType, String filterQuery) {
        if (!SUPPORTED_RESOURCE_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException(
                    "Unsupported resource type for Group membership: " + resourceType + 
                    ". Supported types: " + SUPPORTED_RESOURCE_TYPES);
        }

        try {
            logger.info("🔍 Executing filter preview via FtsGroupService: {}?{}", resourceType, filterQuery);

            // Use FtsGroupService to get preview
            FtsGroupService.PreviewResult groupPreview = 
                ftsGroupService.getPreview(resourceType, filterQuery, MAX_PREVIEW_RESULTS);
            
            long totalCount = groupPreview.getTotalCount();
            List<String> sampleKeys = groupPreview.getSampleKeys();

            // Use N1QL to fetch only display fields
            List<Map<String, Object>> sampleResources = fetchDisplayFields(resourceType, sampleKeys);

            logger.info("✅ Filter preview complete: {} total, {} samples", totalCount, sampleResources.size());
            return new FilterPreviewResult(totalCount, sampleResources, resourceType, filterQuery);

        } catch (Exception e) {
            logger.error("❌ Error executing filter preview", e);
            throw new RuntimeException("Failed to execute filter: " + e.getMessage(), e);
        }
    }

    /**
     * Get all member IDs matching a filter (for group creation/refresh)
     * NOW USES FtsGroupService with built-in pagination support.
     * 
     * @param resourceType The FHIR resource type
     * @param filterQuery FHIR search parameters
     * @param maxMembers Maximum number of members to return (e.g., 10000)
     * @return List of resource IDs (e.g., ["Patient/123", "Patient/456"])
     */
    public List<String> getAllMatchingIds(String resourceType, String filterQuery, int maxMembers) {
        if (!SUPPORTED_RESOURCE_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type: " + resourceType);
        }

        try {
            logger.info("🔍 Fetching all matching IDs via FtsGroupService for {}?{} (max {})", 
                       resourceType, filterQuery, maxMembers);

            // Use FtsGroupService which handles internal pagination
            List<String> ids = ftsGroupService.getAllMatchingKeys(resourceType, filterQuery, maxMembers);

            logger.info("✅ Found {} matching resource IDs", ids.size());
            return ids;

        } catch (Exception e) {
            logger.error("❌ Error fetching matching IDs", e);
            throw new RuntimeException("Failed to fetch matching IDs: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch only display fields using N1QL with USE KEYS for efficiency.
     * This avoids fetching full FHIR resources when we only need a preview.
     */
    private List<Map<String, Object>> fetchDisplayFields(String resourceType, List<String> keys) {
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Cluster cluster = connectionService.getConnection("default");
            String bucketName = "fhir";
            String collectionPath = bucketName + ".Resources." + resourceType;

            // Build N1QL query with USE KEYS for efficient lookup
            StringBuilder query = new StringBuilder();
            query.append("SELECT META().id as id, ");
            
            // Resource-specific field extraction
            switch (resourceType) {
                case "Patient":
                    query.append("name[0].given[0] as given, ")
                         .append("name[0].family as family, ")
                         .append("birthDate, gender ");
                    break;
                case "Practitioner":
                case "RelatedPerson":
                    query.append("name[0].given[0] as given, ")
                         .append("name[0].family as family, ")
                         .append("NULL as birthDate, ")
                         .append("gender ");
                    break;
                case "Device":
                    query.append("deviceName[0].name as deviceName, ")
                         .append("type.coding[0].display as deviceType, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "Medication":
                    query.append("code.coding[0].display as medicationName, ")
                         .append("form.coding[0].display as form, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "Substance":
                    query.append("code.coding[0].display as substanceName, ")
                         .append("category[0].coding[0].display as category, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "Group":
                    query.append("name as groupName, ")
                         .append("quantity, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                case "PractitionerRole":
                    query.append("practitioner.display as practitioner, ")
                         .append("code[0].coding[0].display as role, ")
                         .append("NULL as birthDate, ")
                         .append("NULL as gender ");
                    break;
                default:
                    query.append("NULL as given, NULL as family, NULL as birthDate, NULL as gender ");
            }
            
            query.append("FROM ").append(collectionPath).append(" USE KEYS [");
            
            // Add keys as JSON array elements
            for (int i = 0; i < keys.size(); i++) {
                query.append("'").append(keys.get(i)).append("'");
                if (i < keys.size() - 1) {
                    query.append(", ");
                }
            }
            query.append("]");

            logger.debug("🔍 N1QL Preview Query: {}", query.toString());

            com.couchbase.client.java.query.QueryResult result = cluster.query(query.toString());
            
            List<Map<String, Object>> displayData = new ArrayList<>();
            for (com.couchbase.client.java.json.JsonObject row : result.rowsAsObject()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.getString("id"));
                
                // Format display based on resource type
                if ("Patient".equals(resourceType) || "Practitioner".equals(resourceType) || "RelatedPerson".equals(resourceType)) {
                    String given = row.getString("given");
                    String family = row.getString("family");
                    item.put("name", (given != null ? given + " " : "") + (family != null ? family : ""));
                    item.put("birthDate", row.getString("birthDate"));
                    item.put("gender", row.getString("gender"));
                } else if ("Device".equals(resourceType)) {
                    item.put("name", row.getString("deviceName"));
                    item.put("type", row.getString("deviceType"));
                } else if ("Medication".equals(resourceType)) {
                    item.put("name", row.getString("medicationName"));
                    item.put("form", row.getString("form"));
                } else if ("Substance".equals(resourceType)) {
                    item.put("name", row.getString("substanceName"));
                    item.put("category", row.getString("category"));
                } else if ("Group".equals(resourceType)) {
                    item.put("name", row.getString("groupName"));
                    item.put("quantity", row.getInt("quantity"));
                } else if ("PractitionerRole".equals(resourceType)) {
                    item.put("practitioner", row.getString("practitioner"));
                    item.put("role", row.getString("role"));
                }
                
                displayData.add(item);
            }

            logger.debug("✅ Fetched {} display records via N1QL", displayData.size());
            return displayData;

        } catch (Exception e) {
            logger.error("❌ Error fetching display fields via N1QL", e);
            throw new RuntimeException("Failed to fetch display fields: " + e.getMessage(), e);
        }
    }
}
