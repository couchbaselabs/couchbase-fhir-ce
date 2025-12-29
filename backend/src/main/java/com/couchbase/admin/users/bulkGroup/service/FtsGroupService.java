package com.couchbase.admin.users.bulkGroup.service;

import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.sort.SearchSort;
import com.couchbase.fhir.resources.search.HasParam;
import com.couchbase.fhir.resources.search.SearchQueryResult;
import com.couchbase.fhir.resources.service.FhirSearchParameterResolver;
import com.couchbase.fhir.resources.service.FtsKvSearchService;
import com.couchbase.fhir.resources.service.FtsQueryBuilder;
import com.couchbase.fhir.resources.service.FtsSearchService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FTS search service specialized for Group operations.
 * 
 * Key differences from SearchService:
 * - Supports _has parameter for reverse reference searches
 * - Returns document keys (not full FHIR Bundles)
 * - Internal pagination to get ALL keys (up to maxResults)
 * - No FHIR spec constraints (no Bundle, no _include/_revinclude)
 * - Optimized for bulk membership operations
 * 
 * This service provides a clean API for Group creation and filtering
 * without the complexity of full FHIR search implementation.
 */
@Service
public class FtsGroupService {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsGroupService.class);
    
    private static final int FTS_PAGE_SIZE = 1000;  // Max keys per FTS request (aligned with max Bundle size)
    
    @Autowired
    private FhirSearchParameterResolver searchParameterResolver;
    
    @Autowired
    private FtsQueryBuilder ftsQueryBuilder;
    
    @Autowired
    private FtsSearchService ftsSearchService;
    
    @Autowired
    private FtsKvSearchService ftsKvSearchService;
    
    /**
     * Get ALL matching document keys for group membership (supports _has parameter).
     * Uses internal pagination to fetch all keys up to maxResults.
     * 
     * @param resourceType FHIR resource type (Patient, Practitioner, etc.)
     * @param filterQuery FHIR search filter string (e.g., "name=Smith&birthdate=ge1990")
     * @param maxResults Maximum number of keys to return (e.g., 10000)
     * @return List of document keys (e.g., ["Patient/uuid1", "Patient/uuid2"])
     */
    public List<String> getAllMatchingKeys(String resourceType, String filterQuery, int maxResults) {
        logger.info("🔍 FtsGroupService: Getting all keys for {}?{} (max: {})", 
                   resourceType, filterQuery, maxResults);
        
        // Parse filter into parameter map
        Map<String, List<String>> params = parseFilterQuery(filterQuery);
        
        // Check for _has parameter (special handling)
        HasParam hasParam = detectHasParameter(params);
        if (hasParam != null) {
            return handleHasSearch(resourceType, hasParam, params, maxResults);
        }
        
        // Build FTS queries using modular architecture
        List<SearchQuery> ftsQueries = buildQueriesFromParameters(resourceType, params);
        
        // Execute FTS search with internal pagination
        return fetchAllKeysPaginated(ftsQueries, resourceType, maxResults);
    }
    
    /**
     * Get preview of matching resources (first N + total count).
     * Used for filter preview UI.
     * 
     * @param resourceType FHIR resource type
     * @param filterQuery FHIR search filter string
     * @param sampleSize Number of sample results to return (e.g., 10)
     * @return PreviewResult with sample keys, total count, and display data
     */
    public PreviewResult getPreview(String resourceType, String filterQuery, int sampleSize) {
        logger.info("🔍 FtsGroupService: Getting preview for {}?{} (sample: {})", 
                   resourceType, filterQuery, sampleSize);
        
        // Parse filter and build queries
        Map<String, List<String>> params = parseFilterQuery(filterQuery);
        
        // Check for _has parameter
        HasParam hasParam = detectHasParameter(params);
        if (hasParam != null) {
            // For _has, get sample of intermediate resources
            List<String> sampleKeys = handleHasSearch(resourceType, hasParam, params, sampleSize);
            return new PreviewResult(sampleKeys, sampleKeys.size(), resourceType, filterQuery);
        }
        
        List<SearchQuery> ftsQueries = buildQueriesFromParameters(resourceType, params);
        
        // Execute FTS search for sample + count
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
            ftsQueries, resourceType, 0, sampleSize, getDefaultSort());
        
        List<String> sampleKeys = ftsResult.getDocumentKeys();
        long totalCount = ftsResult.getTotalCount();
        
        logger.info("✅ Preview complete: {} samples, {} total", sampleKeys.size(), totalCount);
        return new PreviewResult(sampleKeys, totalCount, resourceType, filterQuery);
    }
    
    /**
     * Build FTS queries from parsed filter parameters.
     * Uses the modular FhirSearchParameterResolver + FtsQueryBuilder.
     */
    private List<SearchQuery> buildQueriesFromParameters(String resourceType, Map<String, List<String>> params) {
        List<SearchQuery> allQueries = new ArrayList<>();
        
        // Remove control parameters (_has is handled separately)
        Map<String, List<String>> searchParams = new HashMap<>(params);
        searchParams.remove("_has");
        
        for (Map.Entry<String, List<String>> entry : searchParams.entrySet()) {
            String paramName = entry.getKey();
            List<String> values = entry.getValue();
            
            // Step 1: Resolve parameter
            FhirSearchParameterResolver.ResolvedParameter resolved = 
                searchParameterResolver.resolve(resourceType, paramName);
            
            if (resolved == null) {
                logger.warn("Unknown parameter for Group filter: {}", paramName);
                continue;
            }
            
            // Step 2: Build queries
            List<SearchQuery> paramQueries = ftsQueryBuilder.buildQueries(resolved, values);
            if (paramQueries != null && !paramQueries.isEmpty()) {
                allQueries.addAll(paramQueries);
                logger.debug("🔨 Added {} queries for parameter {}", paramQueries.size(), paramName);
            }
        }
        
        return allQueries;
    }
    
    /**
     * Fetch all keys using internal pagination (transparent to caller).
     * Handles FTS limits by making multiple requests (1000 keys per request).
     */
    private List<String> fetchAllKeysPaginated(List<SearchQuery> ftsQueries, String resourceType, int maxResults) {
        List<String> allKeys = new ArrayList<>();
        List<SearchSort> sortFields = getDefaultSort();
        
        int offset = 0;
        int remaining = maxResults;
        
        while (remaining > 0) {
            int pageSize = Math.min(remaining, FTS_PAGE_SIZE);
            
            logger.debug("🔍 Fetching page: offset={}, pageSize={}, remaining={}", offset, pageSize, remaining);
            
            FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
                ftsQueries, resourceType, offset, pageSize, sortFields);
            
            List<String> pageKeys = ftsResult.getDocumentKeys();
            
            if (pageKeys.isEmpty()) {
                logger.debug("🔍 No more keys at offset={}", offset);
                break;
            }
            
            allKeys.addAll(pageKeys);
            
            // If we got fewer keys than requested, we've reached the end
            if (pageKeys.size() < pageSize) {
                logger.debug("🔍 Reached end: got {} keys (expected {})", pageKeys.size(), pageSize);
                break;
            }
            
            offset += pageKeys.size();
            remaining -= pageKeys.size();
        }
        
        logger.info("✅ Fetched {} total keys for {} (max: {})", allKeys.size(), resourceType, maxResults);
        return allKeys;
    }
    
    /**
     * Handle _has parameter searches (reverse reference).
     * Example: Patient?_has:Observation:subject:code=12345
     * Finds Patients who have Observations with code=12345.
     */
    private List<String> handleHasSearch(String resourceType, HasParam hasParam, 
                                         Map<String, List<String>> params, int maxResults) {
        logger.info("🔁 Handling _has search: {} _has {}:{}", 
                   resourceType, hasParam.getTargetResource(), hasParam.getReferenceField());
        
        // Step 1: Build query for target resource (e.g., Observation) with criteria
        Map<String, List<String>> targetCriteria = Map.of(
            hasParam.getCriteriaParam(), List.of(hasParam.getCriteriaValue())
        );
        
        List<SearchQuery> targetQueries = buildQueriesFromParameters(
            hasParam.getTargetResource(), targetCriteria);
        
        // Step 2: Fetch target resources
        List<String> targetKeys = fetchAllKeysPaginated(
            targetQueries, hasParam.getTargetResource(), maxResults);
        
        if (targetKeys.isEmpty()) {
            logger.info("🔁 No target resources found for _has criteria");
            return List.of();
        }
        
        logger.info("🔁 Found {} target {} resources", targetKeys.size(), hasParam.getTargetResource());
        
        // Step 3: Fetch target resources and extract references
        List<Resource> targetResources = ftsKvSearchService.getDocumentsFromKeys(
            targetKeys, hasParam.getTargetResource());
        
        Set<String> referencedKeys = new HashSet<>();
        for (Resource resource : targetResources) {
            String reference = extractReference(resource, hasParam.getReferenceField());
            if (reference != null) {
                referencedKeys.add(reference);
            }
        }
        
        logger.info("✅ _has search complete: found {} {} resources", 
                   referencedKeys.size(), resourceType);
        
        return new ArrayList<>(referencedKeys);
    }
    
    /**
     * Extract reference value from a resource field.
     * Simplified version - uses reflection to get reference field.
     */
    private String extractReference(Resource resource, String fieldName) {
        try {
            // Common reference fields
            if ("subject".equals(fieldName) || "patient".equals(fieldName)) {
                java.lang.reflect.Method getter = resource.getClass().getMethod("get" + 
                    capitalize(fieldName));
                Object result = getter.invoke(resource);
                if (result instanceof org.hl7.fhir.r4.model.Reference) {
                    return ((org.hl7.fhir.r4.model.Reference) result).getReference();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract reference field {}: {}", fieldName, e.getMessage());
        }
        return null;
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    /**
     * Detect _has parameter from parameters map
     */
    private HasParam detectHasParameter(Map<String, List<String>> params) {
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_has:")) {
                String value = entry.getValue().get(0);
                HasParam hasParam = HasParam.parse(key, value);
                if (hasParam != null) {
                    logger.info("🔁 Detected _has parameter: {}", hasParam);
                    return hasParam;
                }
            }
        }
        return null;
    }
    
    /**
     * Parse filter query string into parameter map.
     * Example: "name=Smith&birthdate=ge1990" -> {"name": ["Smith"], "birthdate": ["ge1990"]}
     * IMPORTANT: Decodes BOTH parameter names AND values (critical for _has parameter)
     */
    private Map<String, List<String>> parseFilterQuery(String filterQuery) {
        Map<String, List<String>> params = new HashMap<>();
        
        if (filterQuery == null || filterQuery.isEmpty()) {
            return params;
        }
        
        // Remove leading '?' if present
        String cleaned = filterQuery.startsWith("?") ? filterQuery.substring(1) : filterQuery;
        
        for (String pair : cleaned.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    // CRITICAL: Decode BOTH key and value for proper _has parameter handling
                    String key = java.net.URLDecoder.decode(kv[0], "UTF-8");
                    String value = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                    
                    logger.debug("📝 Parsed parameter: {} = {}", key, value);
                } catch (Exception e) {
                    logger.warn("Failed to decode parameter: {} = {}", kv[0], kv[1]);
                    params.computeIfAbsent(kv[0], k -> new ArrayList<>()).add(kv[1]);
                }
            }
        }
        
        logger.debug("📝 Parsed {} parameters from filter query", params.size());
        return params;
    }
    
    /**
     * Get default sort order for Group searches
     */
    private List<SearchSort> getDefaultSort() {
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
        return sortFields;
    }
    
    /**
     * Preview result container
     */
    public static class PreviewResult {
        private final List<String> sampleKeys;
        private final long totalCount;
        private final String resourceType;
        private final String filter;
        
        public PreviewResult(List<String> sampleKeys, long totalCount, String resourceType, String filter) {
            this.sampleKeys = sampleKeys;
            this.totalCount = totalCount;
            this.resourceType = resourceType;
            this.filter = filter;
        }
        
        public List<String> getSampleKeys() { return sampleKeys; }
        public long getTotalCount() { return totalCount; }
        public String getResourceType() { return resourceType; }
        public String getFilter() { return filter; }
    }
}

