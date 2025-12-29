package com.couchbase.admin.users.bulkGroup.service;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.service.FHIRResourceService;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service to create and manage FHIR Group resources.
 * Uses FHIRResourceService for proper FHIR-compliant storage.
 */
@Service
public class GroupAdminService {

    private static final Logger logger = LoggerFactory.getLogger(GroupAdminService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final int MAX_GROUP_MEMBERS = 10000;

    // Custom extension URLs
    private static final String EXT_CREATION_FILTER = "http://couchbase.fhir.com/StructureDefinition/creation-filter";
    private static final String EXT_CREATED_BY = "http://couchbase.fhir.com/StructureDefinition/created-by";
    private static final String EXT_LAST_REFRESHED = "http://couchbase.fhir.com/StructureDefinition/last-refreshed";
    private static final String EXT_RESOURCE_TYPE = "http://couchbase.fhir.com/StructureDefinition/member-resource-type";

    private final ConnectionService connectionService;
    private final FilterPreviewService filterPreviewService;
    private final FHIRResourceService fhirResourceService;
    private final FhirContext fhirContext;
    public GroupAdminService(ConnectionService connectionService,
                             FilterPreviewService filterPreviewService,
                             FHIRResourceService fhirResourceService,
                             FhirContext fhirContext) {
        this.connectionService = connectionService;
        this.filterPreviewService = filterPreviewService;
        this.fhirResourceService = fhirResourceService;
        this.fhirContext = fhirContext;
        fhirContext.newJsonParser();
        logger.info("✅ GroupAdminService initialized");
    }

    /**
     * Create a new FHIR Group from a filter
     */
    public Group createGroupFromFilter(String name, String resourceType, String filter, String createdBy) {
        return createOrUpdateGroupFromFilter(null, name, resourceType, filter, createdBy);
    }
    
    /**
     * Update an existing FHIR Group (re-run filter with same ID for UPSERT)
     */
    public Group updateGroupFromFilter(String existingId, String name, String resourceType, String filter, String createdBy) {
        return createOrUpdateGroupFromFilter(existingId, name, resourceType, filter, createdBy);
    }
    
    /**
     * Internal method to create or update a FHIR Group from a filter
     */
    private Group createOrUpdateGroupFromFilter(String existingId, String name, String resourceType, String filter, String createdBy) {
        logger.info("{}  FHIR Group '{}' for {} with filter: {}", 
            existingId == null ? "➕ Creating" : "✏️ Updating",
            name, resourceType, filter);
        logger.debug("🔍 Parameters: name='{}', resourceType='{}', filter='{}', createdBy='{}'", 
            name, resourceType, filter, createdBy);

        // Get all matching members
        logger.debug("🔍 Fetching matching IDs...");
        List<String> memberIds = filterPreviewService.getAllMatchingIds(resourceType, filter, MAX_GROUP_MEMBERS);
        logger.debug("✅ Found {} matching IDs", memberIds.size());
        
        if (memberIds.isEmpty()) {
            logger.error("❌ Filter returned no results. Cannot create empty group.");
            throw new IllegalArgumentException("Filter returned no results. Cannot create empty group.");
        }

        if (memberIds.size() >= MAX_GROUP_MEMBERS) {
            logger.warn("⚠️  Filter returned {} results, limiting to {}", memberIds.size(), MAX_GROUP_MEMBERS);
        }

        // Build FHIR Group resource
        logger.debug("🔍 Building FHIR Group resource...");
        Group group = new Group();
        
        // Use existing ID for update, generate new for create
        String groupId = existingId != null ? existingId : UUID.randomUUID().toString();
        group.setId(groupId);
        logger.debug("✅ Group ID: {}", group.getId());
        
        // Meta - increment version if updating
        Meta meta = new Meta();
        if (existingId != null) {
            // For updates, try to get existing version and increment
            Optional<Group> existingOpt = getGroupById(existingId);
            if (existingOpt.isPresent() && existingOpt.get().getMeta() != null) {
                String existingVersion = existingOpt.get().getMeta().getVersionId();
                try {
                    int version = Integer.parseInt(existingVersion);
                    meta.setVersionId(String.valueOf(version + 1));
                } catch (NumberFormatException e) {
                    meta.setVersionId("2"); // Default to 2 if we can't parse
                }
            } else {
                meta.setVersionId("2");
            }
        } else {
            meta.setVersionId("1");
        }
        meta.setLastUpdated(Date.from(Instant.now()));
        meta.addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-group");
        
        // Add created-by tag
        if (createdBy != null && !createdBy.isEmpty()) {
            Coding tag = new Coding();
            tag.setSystem("http://couchbase.fhir.com/fhir/custom-tags");
            tag.setCode("created-by");
            tag.setDisplay("user:" + createdBy);
            meta.addTag(tag);
        }
        group.setMeta(meta);

        // Identifier
        Identifier identifier = new Identifier();
        identifier.setSystem("http://couchbase.fhir.com/group-id");
        identifier.setValue(group.getId());
        group.addIdentifier(identifier);

        // Core properties
        group.setActive(true);
        group.setType(Group.GroupType.PERSON); // Default to person, can be overridden
        group.setActual(true); // This is an actual list of members, not a definition
        group.setName(name);
        group.setQuantity(memberIds.size());

        // Add members
        for (String memberId : memberIds) {
            Group.GroupMemberComponent member = new Group.GroupMemberComponent();
            Reference ref = new Reference(memberId);
            member.setEntity(ref);
            group.addMember(member);
        }

        // Extensions
        Extension filterExt = new Extension();
        filterExt.setUrl(EXT_CREATION_FILTER);
        // Store query-only string (e.g., "name=Baxter")
        filterExt.setValue(new StringType(filter));
        group.addExtension(filterExt);

        Extension createdByExt = new Extension();
        createdByExt.setUrl(EXT_CREATED_BY);
        createdByExt.setValue(new StringType(createdBy != null ? createdBy : "anonymous"));
        group.addExtension(createdByExt);

        Extension lastRefreshedExt = new Extension();
        lastRefreshedExt.setUrl(EXT_LAST_REFRESHED);
        lastRefreshedExt.setValue(new DateTimeType(Date.from(Instant.now())));
        group.addExtension(lastRefreshedExt);

        Extension resourceTypeExt = new Extension();
        resourceTypeExt.setUrl(EXT_RESOURCE_TYPE);
        resourceTypeExt.setValue(new StringType(resourceType));
        group.addExtension(resourceTypeExt);

        // Store via Couchbase SDK (upsert)
        logger.debug("🔍 Upserting Group via Couchbase SDK...");
        Cluster cluster = connectionService.getConnection("default");
        com.couchbase.client.java.Collection collection = cluster.bucket(BUCKET_NAME)
            .scope("Resources")
            .collection("General");
        String documentKey = "Group/" + groupId;
        String json = fhirContext.newJsonParser().encodeResourceToString(group);
        collection.upsert(documentKey, JsonObject.fromJson(json));
        logger.debug("✅ Group upserted: {}", documentKey);

        logger.info("✅ Group {} successfully: {} with {} members", 
            existingId == null ? "created" : "updated",
            group.getId(), memberIds.size());
        return group;
    }

    /**
     * Get a Group by ID using FHIRResourceService
     */
    public Optional<Group> getGroupById(String id) {
        try {
            return fhirResourceService.getService(Group.class)
                    .read("Group", id, BUCKET_NAME);
        } catch (Exception e) {
            logger.debug("Group not found: {}", id);
            return Optional.empty();
        }
    }

    // Deprecated: use getAllGroupSummaries() for list view performance and clean IDs.

    /**
     * Get all groups as lightweight summary rows directly from N1QL
     * Avoids loading full HAPI resources for list view performance.
     */
    public List<GroupSummaryRow> getAllGroupSummaries() {
        try {
            Cluster cluster = connectionService.getConnection("default");

            String query = String.format(
                    "SELECT " +
                    "  g.name, " +
                    "  g.id, " +
                    "  g.quantity, " +
                    "  (ARRAY e.valueString " +
                    "   FOR e IN g.extension " +
                    "   WHEN e.url = '%s' " +
                    "   END)[0] AS memberResourceType, " +
                    "  (ARRAY e.valueString " +
                    "   FOR e IN g.extension " +
                    "   WHEN e.url = '%s' " +
                    "   END)[0] AS creationFilter, " +
                    "  (ARRAY e.valueString " +
                    "   FOR e IN g.extension " +
                    "   WHEN e.url = '%s' " +
                    "   END)[0] AS createdBy, " +
                    "  (ARRAY e.valueDateTime " +
                    "   FOR e IN g.extension " +
                    "   WHEN e.url = '%s' " +
                    "   END)[0] AS lastRefreshed, " +
                    "  g.type, " +
                    "  g.meta.lastUpdated " +
                    "FROM `%s`.`Resources`.`General` AS g " +
                    "USE INDEX (ftsGeneral USING FTS) " +
                    "WHERE SEARCH(" +
                    "        g, " +
                    "        {" +
                    "          \"query\": {" +
                    "            \"term\": \"Group\", " +
                    "            \"field\": \"resourceType\"" +
                    "          }" +
                    "        }, " +
                    "        { \"index\": \"%s.Resources.ftsGeneral\" }" +
                    "      ) " +
                    "ORDER BY g.meta.lastUpdated DESC",
                    EXT_RESOURCE_TYPE,
                    EXT_CREATION_FILTER,
                    EXT_CREATED_BY,
                    EXT_LAST_REFRESHED,
                    BUCKET_NAME,
                    BUCKET_NAME);

            logger.info("🔍 Querying Group summaries from fhir.Resources.General using FTS");
            QueryResult result = cluster.query(query);

            List<GroupSummaryRow> rows = new ArrayList<>();
            for (var row : result.rowsAsObject()) {
                GroupSummaryRow dto = new GroupSummaryRow();
                dto.setId(row.getString("id")); // logical UUID
                dto.setName(row.getString("name"));
                dto.setResourceType(row.getString("memberResourceType"));
                dto.setFilter(row.getString("creationFilter"));
                dto.setMemberCount(row.getInt("quantity"));
                dto.setCreatedBy(row.getString("createdBy"));
                // Convert timestamps to string for UI rendering
                Object lu = row.get("lastUpdated");
                dto.setLastUpdated(lu != null ? lu.toString() : null);
                Object lr = row.get("lastRefreshed");
                dto.setLastRefreshed(lr != null ? lr.toString() : null);
                rows.add(dto);
            }

            logger.info("✅ Found {} group summaries", rows.size());
            return rows;
        } catch (Exception e) {
            logger.error("❌ Error fetching group summaries", e);
            return new ArrayList<>();
        }
    }

    /**
     * Lightweight DTO for group list view
     */
    public static class GroupSummaryRow {
        private String id;
        private String name;
        private String filter;
        private String resourceType;
        private int memberCount;
        private String createdBy;
        private String lastUpdated;
        private String lastRefreshed;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFilter() { return filter; }
        public void setFilter(String filter) { this.filter = filter; }
        public String getResourceType() { return resourceType; }
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }
        public int getMemberCount() { return memberCount; }
        public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
        public String getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
        public String getLastRefreshed() { return lastRefreshed; }
        public void setLastRefreshed(String lastRefreshed) { this.lastRefreshed = lastRefreshed; }
    }

    /**
     * Refresh a group by re-running its filter
     */
    public Group refreshGroup(String id) {
        logger.info("🔄 Refreshing group: {}", id);

        Group existingGroup = getGroupById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + id));

        // Extract query-only filter and resourceType from extensions
        Extension filterExt = existingGroup.getExtensionByUrl(EXT_CREATION_FILTER);
        Extension resourceTypeExt = existingGroup.getExtensionByUrl(EXT_RESOURCE_TYPE);
        if (filterExt == null || filterExt.getValue() == null || resourceTypeExt == null || resourceTypeExt.getValue() == null) {
            throw new IllegalStateException("Group missing filter or resource type - cannot refresh");
        }
        String filter = filterExt.getValue().primitiveValue();
        String resourceType = resourceTypeExt.getValue().primitiveValue();

        // Get new member list
        List<String> newMemberIds = filterPreviewService.getAllMatchingIds(resourceType, filter, MAX_GROUP_MEMBERS);

        // Update members
        existingGroup.getMember().clear();
        for (String memberId : newMemberIds) {
            Group.GroupMemberComponent member = new Group.GroupMemberComponent();
            Reference ref = new Reference(memberId);
            member.setEntity(ref);
            existingGroup.addMember(member);
        }

        // Update quantity
        existingGroup.setQuantity(newMemberIds.size());

        // Update meta
        existingGroup.getMeta().setVersionId(String.valueOf(
                Integer.parseInt(existingGroup.getMeta().getVersionId()) + 1));
        existingGroup.getMeta().setLastUpdated(Date.from(Instant.now()));

        // Update last-refreshed extension
        Extension lastRefreshedExt = existingGroup.getExtensionByUrl(EXT_LAST_REFRESHED);
        if (lastRefreshedExt != null) {
            lastRefreshedExt.setValue(new DateTimeType(Date.from(Instant.now())));
        } else {
            Extension newExt = new Extension();
            newExt.setUrl(EXT_LAST_REFRESHED);
            newExt.setValue(new DateTimeType(Date.from(Instant.now())));
            existingGroup.addExtension(newExt);
        }

        // Upsert updated group via Couchbase SDK
        logger.debug("🔍 Upserting refreshed Group via Couchbase SDK...");
        Cluster cluster = connectionService.getConnection("default");
        com.couchbase.client.java.Collection collection = cluster.bucket(BUCKET_NAME)
            .scope("Resources")
            .collection("General");
        String documentKey = "Group/" + id;
        String json = fhirContext.newJsonParser().encodeResourceToString(existingGroup);
        collection.upsert(documentKey, JsonObject.fromJson(json));
        logger.info("✅ Group refreshed: {} now has {} members", id, newMemberIds.size());
        return existingGroup;
    }

    /**
     * Remove a specific member from a group
     */
    public Group removeMember(String groupId, String memberReference) {
        logger.info("➖ Removing member {} from group {}", memberReference, groupId);

        Group group = getGroupById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));

        boolean removed = group.getMember().removeIf(m -> 
                m.getEntity().getReference().equals(memberReference));

        if (!removed) {
            throw new IllegalArgumentException("Member not found in group: " + memberReference);
        }

        // Update quantity
        group.setQuantity(group.getMember().size());

        // Update meta
        group.getMeta().setVersionId(String.valueOf(
                Integer.parseInt(group.getMeta().getVersionId()) + 1));
        group.getMeta().setLastUpdated(Date.from(Instant.now()));

        // Upsert updated group via Couchbase SDK
        logger.debug("🔍 Upserting updated Group via Couchbase SDK...");
        Cluster cluster = connectionService.getConnection("default");
        com.couchbase.client.java.Collection collection = cluster.bucket(BUCKET_NAME)
            .scope("Resources")
            .collection("General");
        String documentKey = "Group/" + groupId;
        String json = fhirContext.newJsonParser().encodeResourceToString(group);
        collection.upsert(documentKey, JsonObject.fromJson(json));

        logger.info("✅ Member removed from group {}", groupId);
        return group;
    }

    /**
     * Delete a Group using direct KV delete
     */
    public void deleteGroup(String id) {
        logger.info("🗑️  Deleting group: {}", id);

        if (getGroupById(id).isEmpty()) {
            throw new IllegalArgumentException("Group not found: " + id);
        }

        try {
            Cluster cluster = connectionService.getConnection("default");
            String targetCollection = "General"; // Groups go to General collection
            com.couchbase.client.java.Collection collection = cluster.bucket(BUCKET_NAME)
                    .scope("Resources")
                    .collection(targetCollection);
            
            String documentKey = "Group/" + id;  // Use FHIR standard key format
            collection.remove(documentKey);
            logger.info("✅ Group deleted: {}", id);
        } catch (Exception e) {
            logger.error("❌ Error deleting group: {}", e.getMessage());
            throw new RuntimeException("Failed to delete group: " + e.getMessage(), e);
        }
    }

    /**
     * Extract filter info from a Group for display
     */
    public static class GroupSummary {
        private final String id;
        private final String name;
        private final String filter;
        private final String resourceType;
        private final int memberCount;
        private final String createdBy;
        private final Date lastUpdated;
        private final Date lastRefreshed;

        public GroupSummary(Group group) {
            this.id = group.getId();
            this.name = group.getName();
            this.memberCount = group.getQuantity();
            this.lastUpdated = group.getMeta().getLastUpdated();

            // Extract from extensions
            Extension filterExt = group.getExtensionByUrl(EXT_CREATION_FILTER);
            this.filter = filterExt != null ? filterExt.getValue().primitiveValue() : null;

            Extension resourceTypeExt = group.getExtensionByUrl(EXT_RESOURCE_TYPE);
            this.resourceType = resourceTypeExt != null ? resourceTypeExt.getValue().primitiveValue() : "Unknown";

            Extension createdByExt = group.getExtensionByUrl(EXT_CREATED_BY);
            this.createdBy = createdByExt != null ? createdByExt.getValue().primitiveValue() : "Unknown";

            Extension lastRefreshedExt = group.getExtensionByUrl(EXT_LAST_REFRESHED);
            this.lastRefreshed = lastRefreshedExt != null ? 
                    ((DateTimeType) lastRefreshedExt.getValue()).getValue() : null;
        }

        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getFilter() { return filter; }
        public String getResourceType() { return resourceType; }
        public int getMemberCount() { return memberCount; }
        public String getCreatedBy() { return createdBy; }
        public Date getLastUpdated() { return lastUpdated; }
        public Date getLastRefreshed() { return lastRefreshed; }
    }
}

