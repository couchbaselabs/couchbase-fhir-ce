package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.fhir.resources.interceptor.RequestPerfBagUtils;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.sort.SearchSort;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.search.*;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import ca.uhn.fhir.model.api.Include;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ca.uhn.fhir.parser.IParser;
import com.couchbase.common.config.FhirConfig;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized search service for FHIR resources using the new FTS/KV pagination strategy.
 * Provides fast, scalable search operations with optimal pagination performance.
 */
@Service
public class SearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    
    // New pagination constants
    private static final int DEFAULT_PAGE_SIZE = 20;  // New permanent default
    // Removed MAX_COUNT_PER_PAGE (no mid-level cap; rely on absolute safety cap)
    // Removed MAX_FTS_FETCH_SIZE (FTS service now governs fetch sizing internally)
    private static final int MAX_BUNDLE_SIZE = 1000;  // Hard cap on total Bundle resources (primaries + secondaries combined)
    
    // Fastpath attribute key for storing UTF-8 bytes in request (2x memory savings vs String)
    public static final String FASTPATH_BYTES_ATTRIBUTE = "com.couchbase.fhir.fastpath.bytes";
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirBucketValidator bucketValidator;
    
    @Autowired
    private FhirSearchParameterPreprocessor searchPreprocessor;
    
    @Autowired
    private SearchStateManager searchStateManager;
    
    @Autowired
    private FhirConfig fhirConfig;
    
    @Autowired
    private FtsKvSearchService ftsKvSearchService;
    
    @Autowired
    private FtsSearchService ftsSearchService;
    
    @Autowired
    private BatchKvService batchKvService;
    
    @Autowired
    private FastJsonBundleBuilder fastJsonBundleBuilder;
    
    @Autowired
    private com.couchbase.fhir.resources.config.BundleFastpathProperties fastpathProperties;

    // Centralized FHIR server base URL configuration (backed by config.yaml app.baseUrl)
    @Autowired
    private com.couchbase.common.config.FhirServerConfig fhirServerConfig;
    
    @Autowired
    private IncludeReferenceExtractor includeReferenceExtractor;
    
    @Autowired
    private FhirSearchParameterResolver searchParameterResolver;
    
    @Autowired
    private FtsQueryBuilder ftsQueryBuilder;
    
    /**
     * Resolve conditional operations by finding matching resources.
     * Returns result indicating ZERO, ONE(id), or MANY matches for conditional operations.
     * 
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @param criteria Search criteria as key-value pairs
     * @return ResolveResult indicating ZERO, ONE(id), or MANY matches
     */
    public ResolveResult resolveConditional(String resourceType, Map<String, List<String>> criteria) {
        logger.debug("🔍 Resolving conditional operation: {} with criteria: {}", resourceType, criteria);
        
        // Use regular search with small limit for conditional operations
        Bundle result = searchForConditional(resourceType, criteria, 2);
        
        int totalResults = result.getTotal();
        if (totalResults == 0) {
            return ResolveResult.zero();
        } else if (totalResults == 1) {
            String resourceId = result.getEntry().get(0).getResource().getIdElement().getIdPart();
            logger.debug("🔍 Conditional operation: Single match found: {}", resourceId);
            return ResolveResult.one(resourceId);
        } else {
            logger.warn("🔍 Conditional operation: Multiple matches found ({}), returning MANY", totalResults);
            return ResolveResult.many();
        }
    }
    
    /**
     * Internal search method for conditional operations with small result limit
     */
    private Bundle searchForConditional(String resourceType, Map<String, List<String>> criteria, int limit) {
        // Convert Map<String, List<String>> to Map<String, String[]> for search
        Map<String, String[]> searchParams = criteria.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toArray(new String[0])
                ));
        
        // Create minimal request details
        ca.uhn.fhir.rest.server.servlet.ServletRequestDetails requestDetails = 
            new ca.uhn.fhir.rest.server.servlet.ServletRequestDetails();
        requestDetails.setParameters(searchParams);
        
        // Override count parameter to limit results
        searchParams.put("_count", new String[]{String.valueOf(limit)});
        
        return search(resourceType, requestDetails);
    }
    
    /**
     * Direct search for Bundle processing (thin facade).
     * USES FASTPATH when enabled for maximum performance!
     * Used for batch/transaction Bundle requests.
     * 
     * @param resourceType FHIR resource type
     * @param params Search parameters
     * @param bucketName Tenant bucket name (for constructing base URL)
     * @return Bundle with search results
     */
    public Bundle searchDirect(String resourceType, Map<String, String[]> params, String bucketName) {
        // Single-tenant: tenant context switching is obsolete; bucketName ignored for URL construction
        // (kept as parameter for backward compatibility with callers; can be removed in a later refactor)
            
            ca.uhn.fhir.rest.server.servlet.ServletRequestDetails requestDetails = 
                new ca.uhn.fhir.rest.server.servlet.ServletRequestDetails();
            requestDetails.setParameters(params);
            
            // Single-tenant URL model: external URLs no longer include bucketName segment.
            // Normalized base URL already ends with /fhir (config.yaml app.baseUrl)
            String serverBase = fhirServerConfig.getNormalizedBaseUrl();
            String syntheticUrl = serverBase + "/" + resourceType; // collection URL (e.g. .../fhir/Patient)
            requestDetails.setCompleteUrl(syntheticUrl);
            requestDetails.setFhirServerBase(serverBase);
            
            // Call search - it may use fastpath and store bytes in userData
            Bundle result = search(resourceType, requestDetails);
            
            // Check if fastpath was used (bytes stored in userData)
            Object fastpathBytes = requestDetails.getUserData().get(FASTPATH_BYTES_ATTRIBUTE);
            if (fastpathBytes instanceof byte[]) {
                // Fastpath was used - parse the JSON bytes to HAPI Bundle
                // This is MUCH faster than full HAPI serialization (10x faster)
                // We only parse the structure, resources stay as JSON internally
                byte[] jsonBytes = (byte[]) fastpathBytes;
                String jsonString = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                
                IParser parser = fhirContext.newJsonParser();
                Bundle parsedBundle = parser.parseResource(Bundle.class, jsonString);
                
                logger.debug("🚀 FASTPATH (batch): Parsed {} bytes to Bundle with {} entries", 
                            jsonBytes.length, parsedBundle.getEntry() != null ? parsedBundle.getEntry().size() : 0);
                
                return parsedBundle;
            }
            
            // No fastpath, return the Bundle as-is
            return result;
    }
    
    /**
     * Key-only search result containing document keys and total count.
     * Used when full resources are not needed (e.g., Group membership, display previews).
     */
    public static class KeySearchResult {
        private final List<String> keys;
        private final long totalCount;
        
        public KeySearchResult(List<String> keys, long totalCount) {
            this.keys = keys;
            this.totalCount = totalCount;
        }
        
        public List<String> getKeys() { return keys; }
        public long getTotalCount() { return totalCount; }
    }
    
    /**
     * Key-only search operation returning document keys without fetching full resources.
     * Uses same HAPI parameter validation and FTS query building as search(), but skips BatchKvService.
     * 
     * Limitations:
     * - Does not support _include, _revinclude, or chained searches (throws exception)
     * - Does not support _summary or _elements (not applicable for key-only)
     * - Does support all standard search parameters (name, identifier, date ranges, etc.)
     * 
     * @param resourceType FHIR resource type
     * @param requestDetails HAPI FHIR request details containing search parameters
     * @return KeySearchResult with document keys (e.g., ["Patient/123"]) and total count
     */
    public KeySearchResult searchForKeys(String resourceType, RequestDetails requestDetails) {
        String bucketName = TenantContextHolder.getTenantId();
        
        long searchStartMs = System.currentTimeMillis();
        
        logger.debug("🔑 SearchService.searchForKeys: {} in bucket {} | reqId={}", resourceType, bucketName, 
            RequestPerfBagUtils.getCurrentRequestId(requestDetails));
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw e;
        }
        
        // Extract parameters (same as search())
        Map<String, String[]> rawParams = requestDetails.getParameters();
        Map<String, List<String>> allParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Arrays.asList(e.getValue())
                ));
        
        Map<String, String> searchParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue()[0]
                ));
        
        // Check for unsupported features (chain, _include, _revinclude, _has)
        ChainParam chainParam = detectChainParameter(searchParams, resourceType);
        HasParam hasParam = detectHasParameter(searchParams);
        List<String> includeValues = allParams.get("_include");
        List<String> revIncludeValues = allParams.get("_revinclude");
        
        if (chainParam != null) {
            throw new InvalidRequestException("Chained searches are not supported for key-only queries");
        }
        if (hasParam != null) {
            throw new InvalidRequestException("_has parameter is not supported for key-only queries");
        }
        if (includeValues != null && !includeValues.isEmpty()) {
            throw new InvalidRequestException("_include is not supported for key-only queries");
        }
        if (revIncludeValues != null && !revIncludeValues.isEmpty()) {
            throw new InvalidRequestException("_revinclude is not supported for key-only queries");
        }
        
        // Validate search parameters
        Map<String, List<String>> paramsToValidate = new java.util.HashMap<>(allParams);
        paramsToValidate.remove("_summary");
        paramsToValidate.remove("_elements");
        paramsToValidate.remove("_count");
        paramsToValidate.remove("_sort");
        paramsToValidate.remove("_total");
        
        try {
            searchPreprocessor.validateSearchParameters(resourceType, paramsToValidate);
        } catch (FhirSearchValidationException e) {
            throw new InvalidRequestException(e.getUserFriendlyMessage());
        }
        
        // Parse parameters
        int count = parseCountParameter(searchParams);
        List<SearchSort> sortFields = parseSortParameter(searchParams);
        
        // Remove control parameters from search criteria
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");
        searchParams.remove("_include");
        searchParams.remove("_revinclude");
        
        // Build FTS queries (same as search())
        SearchQueryResult searchQueryResult = buildSearchQueries(resourceType, allParams);
        List<SearchQuery> ftsQueries = searchQueryResult.getFtsQueries();
        
        logger.debug("🔑 Built {} FTS queries for key-only search", ftsQueries.size());
        
        // Execute FTS search for keys (NO BatchKvService call!)
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
            ftsQueries, resourceType, 0, count + 1, sortFields);
        
        List<String> allKeys = ftsResult.getDocumentKeys();
        long totalCount = ftsResult.getTotalCount();
        
        // Apply pagination limit (return up to 'count' keys)
        List<String> resultKeys = allKeys.size() > count ? 
            allKeys.subList(0, count) : allKeys;
        
        logger.debug("🔑 searchForKeys complete: {} keys returned, {} total | duration: {} ms", 
            resultKeys.size(), totalCount, System.currentTimeMillis() - searchStartMs);
        
        RequestPerfBagUtils.addTiming(requestDetails, "search_keys", System.currentTimeMillis() - searchStartMs);
        
        return new KeySearchResult(resultKeys, totalCount);
    }
    
    /**
     * Full search operation returning complete Bundle with resources.
     * 
     * @param resourceType FHIR resource type
     * @param requestDetails HAPI FHIR request details containing search parameters
     * @return Bundle with matching resources
     */
    public Bundle search(String resourceType, RequestDetails requestDetails) {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Track search start time in performance bag
        long searchStartMs = System.currentTimeMillis();
        
        logger.debug("🔍 SearchService.search: {} in bucket {} | reqId={}", resourceType, bucketName, 
            RequestPerfBagUtils.getCurrentRequestId(requestDetails));
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw e; // Re-throw as-is since it already extends InvalidRequestException
        }
        
        // Extract and validate parameters
        Map<String, String[]> rawParams = requestDetails.getParameters();
        Map<String, List<String>> allParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Arrays.asList(e.getValue())
                ));
        
        // Flatten parameters for processing
        Map<String, String> searchParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue()[0]
                ));
        
        // Check for chained parameters FIRST (before validation)
        ChainParam chainParam = detectChainParameter(searchParams, resourceType);

        //Check for Has Parameters FIRST
        HasParam hasParam = detectHasParameter(searchParams);

        // Validate search parameters (excluding chain, _include, _revinclude - they have special syntax)
        Map<String, List<String>> paramsToValidate = new java.util.HashMap<>(allParams);
        
        // Remove chain parameter from validation since validator doesn't understand chain syntax
        if (chainParam != null) {
             paramsToValidate.remove(chainParam.getOriginalParameter());
            logger.debug("🔍 Detected chain parameter '{}', excluding from validation", chainParam.getOriginalParameter());
        }

        if(hasParam != null) {
            paramsToValidate.remove("_has");
            logger.debug("🔍 Detected HAS parameter, excluding from validation");
        }
        
        // Remove _include/_revinclude from validation since validator doesn't parse their syntax correctly
        // (e.g., Encounter:subject:Patient causes validator to see "subject:Patient" as a parameter name)
        paramsToValidate.remove("_include");
        paramsToValidate.remove("_revinclude");
        
        try {
            searchPreprocessor.validateSearchParameters(resourceType, paramsToValidate);
        } catch (FhirSearchValidationException e) {
            throw new InvalidRequestException(e.getUserFriendlyMessage());
        }
        
    // Parse FHIR standard parameters
    SummaryEnum summaryMode = parseSummaryParameter(searchParams);
    Set<String> elements = parseElementsParameter(searchParams);
    int count = parseCountParameter(searchParams);
    List<SearchSort> sortFields = parseSortParameter(searchParams);
        String totalMode = parseTotalParameter(searchParams);
        
        // Check for _revinclude parameters (can be multiple) using HAPI's Include class
        List<Include> revIncludes = new ArrayList<>();
        List<String> revIncludeValues = allParams.get("_revinclude");
        if (revIncludeValues != null && !revIncludeValues.isEmpty()) {
            for (String revIncludeValue : revIncludeValues) {
                try {
                    Include revInclude = new Include(revIncludeValue, true); // true = reverse include
                    revIncludes.add(revInclude);
                    logger.debug("🔍 Parsed HAPI RevInclude: {}", revInclude);
                } catch (Exception e) {
                    throw new InvalidRequestException("Invalid _revinclude parameter: " + revIncludeValue + " - " + e.getMessage());
                }
            }
            logger.debug("🔍 Total _revinclude parameters: {}", revIncludes.size());
        }
        
        // Check for _include parameters (can be multiple) using HAPI's Include class
        List<Include> includes = new ArrayList<>();
        List<String> includeValues = allParams.get("_include");
        if (includeValues != null && !includeValues.isEmpty()) {
            for (String includeValue : includeValues) {
                try {
                    Include include = new Include(includeValue);
                    includes.add(include);
                    logger.debug("🔍 Parsed HAPI Include: {}", include);
                } catch (Exception e) {
                    throw new InvalidRequestException("Invalid _include parameter: " + includeValue + " - " + e.getMessage());
                }
            }
            logger.debug("🔍 Total _include parameters: {}", includes.size());
        }


        
    // Remove control parameters from search criteria (we already captured userExplicitCount)
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");
        searchParams.remove("_revinclude");
        searchParams.remove("_include");
        
        // Remove chain parameter from search criteria if found (both from searchParams and allParams)
        if (chainParam != null) {
            searchParams.remove(chainParam.getOriginalParameter());
            allParams.remove(chainParam.getOriginalParameter());
            logger.debug("🔍 Removed chain parameter '{}' from query building", chainParam.getOriginalParameter());
        }

        if(hasParam != null) {
            searchParams.remove("_has");
            allParams.remove("_has");
            logger.debug("🔍 Removed has parameter from query building");
        }
        
        // Build search queries - use allParams to handle multiple values for the same parameter
        SearchQueryResult searchQueryResult = buildSearchQueries(resourceType, allParams);
        List<SearchQuery> ftsQueries = searchQueryResult.getFtsQueries();
        // Note: N1QL filters removed - using pure FTS/KV architecture
        
        logger.debug("🔍 SearchService: Built {} FTS queries for {}", ftsQueries.size(), resourceType);
        // Handle count-only queries
        if ("accurate".equals(totalMode) && count == 0) {
            Bundle result = handleCountOnlyQuery(ftsQueries, resourceType, bucketName);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a chained search (HIGHEST PRIORITY - most specific)
        if (chainParam != null) {
            Bundle result = handleChainSearch(resourceType, ftsQueries, chainParam, includes, count,
                                   sortFields, summaryMode, elements, totalMode, bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }

        if(hasParam != null){
            Bundle result  = handleHasSearch(resourceType , hasParam , ftsQueries , bucketName , sortFields , count , requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a _revinclude search (can have multiple _revinclude parameters)
        if (!revIncludes.isEmpty()) {
            // Check if fastpath is enabled (beta: no _summary/_elements support)
            boolean useFastpath = fastpathProperties.isEnabled() && 
                                  (summaryMode == null || summaryMode == SummaryEnum.FALSE) && 
                                  (elements == null || elements.isEmpty());
            
        if (useFastpath) {
            logger.debug("🚀 FASTPATH ENABLED: Using JSON fastpath for _revinclude search");
            byte[] resultBytes = handleMultipleRevIncludeSearchFastpath(resourceType, ftsQueries, revIncludes, count, 
                                     bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            
            // Store UTF-8 bytes in request attribute for interceptor (2x memory savings vs String)
            requestDetails.getUserData().put(FASTPATH_BYTES_ATTRIBUTE, resultBytes);
                
                // Return empty placeholder Bundle (interceptor will replace with JSON)
                Bundle placeholder = new Bundle();
                placeholder.setType(Bundle.BundleType.SEARCHSET);
                return placeholder;
            } else {
                Bundle result = handleMultipleRevIncludeSearch(resourceType, ftsQueries, revIncludes, count, 
                                            summaryMode, elements, totalMode, bucketName, requestDetails);
                RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
                RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
                return result;
            }
        }
        
        // Check if this is a _include search (can have multiple _include parameters)
        if (!includes.isEmpty()) {
            try {
                // Check if fastpath is enabled (beta: no _summary/_elements support)
                boolean useFastpath = fastpathProperties.isEnabled() && 
                                      (summaryMode == null || summaryMode == SummaryEnum.FALSE) && 
                                      (elements == null || elements.isEmpty());
                
            if (useFastpath) {
                logger.debug("🚀 FASTPATH ENABLED: Using JSON fastpath for _include search");
                byte[] resultBytes = handleMultipleIncludeSearchFastpath(resourceType, ftsQueries, includes, count, 
                                         bucketName, requestDetails);
                RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
                
                // Store UTF-8 bytes in request attribute for interceptor (2x memory savings vs String)
                requestDetails.getUserData().put(FASTPATH_BYTES_ATTRIBUTE, resultBytes);
                    
                    // Return empty placeholder Bundle (interceptor will replace with JSON)
                    Bundle placeholder = new Bundle();
                    placeholder.setType(Bundle.BundleType.SEARCHSET);
                    return placeholder;
                } else {
                    logger.debug("🔍 About to call handleMultipleIncludeSearch for {} with {} includes", resourceType, includes.size());
                    Bundle result = handleMultipleIncludeSearch(resourceType, ftsQueries, includes, count, 
                                             summaryMode, elements, totalMode, bucketName, requestDetails);
                    logger.debug("🔍 handleMultipleIncludeSearch completed successfully");
                    RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
                    RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
                    return result;
                }
            } catch (Exception e) {
                if (isNoActiveConnectionError(e)) {
                    logger.error("🔍 ❌ Include search failed: No active Couchbase connection");
                } else {
                    logger.error("🔍 ❌ Exception in handleMultipleIncludeSearch: {} - {}", e.getClass().getName(), e.getMessage());
                }
                throw e; // Re-throw to let HAPI handle it
            }
        }
        
        // Check if fastpath is enabled for regular search (beta: no _summary/_elements support)
        boolean useFastpath = fastpathProperties.isEnabled() && 
                              (summaryMode == null || summaryMode == SummaryEnum.FALSE) && 
                              (elements == null || elements.isEmpty());
        
        if (useFastpath) {
            logger.debug("🚀 FASTPATH ENABLED: Using JSON fastpath for regular search");
            byte[] resultBytes = handleRegularSearchFastpath(resourceType, ftsQueries, count, sortFields,
                                         bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            
            // Store UTF-8 bytes in request attribute for interceptor (2x memory savings vs String)
            requestDetails.getUserData().put(FASTPATH_BYTES_ATTRIBUTE, resultBytes);
            
            // Return empty placeholder Bundle (interceptor will replace with JSON)
            Bundle placeholder = new Bundle();
            placeholder.setType(Bundle.BundleType.SEARCHSET);
            return placeholder;
        }
        
        // Always use the new pagination strategy for regular searches
    // logger.debug("🚀 Using new pagination strategy for {} search (userExplicitCount={})", resourceType, userExplicitCount);
        Bundle result = executeSearchWithNewPagination(resourceType, ftsQueries, count, sortFields, 
                                                      summaryMode, elements, totalMode, bucketName, requestDetails);
        // Track search timing
        RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
        RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
        return result;
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Detect if any parameter is a chained parameter
     */
    private ChainParam detectChainParameter(Map<String, String> searchParams, String resourceType) {
        for (Map.Entry<String, String> entry : searchParams.entrySet()) {
            String paramKey = entry.getKey();
            String paramValue = entry.getValue();
            
            // Skip control parameters
            if (paramKey.startsWith("_")) {
                continue;
            }
            
            ChainParam chainParam = ChainParam.parse(paramKey, paramValue, resourceType, fhirContext);
            if (chainParam != null) {
                logger.debug("🔗 Detected chain parameter: {}", chainParam);
                return chainParam; // For now, support only one chain parameter per search
            }
        }
        
        return null;
    }

    /**
     * Detect if any parameter is a Has parameter
     */
    private HasParam detectHasParameter(Map<String, String> searchParams) {
        for (Map.Entry<String, String> e : searchParams.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();

            if (!key.startsWith("_has:")) {
                continue;
            }

            HasParam hp = HasParam.parse(key, value);
            if (hp != null) {
                logger.info("🔁 Detected HAS parameter: {}", hp);
                return hp;
            }
        }

        return null;
    }
    
    /**
     * Build search queries from criteria parameters.
     * NEW: Uses modular FhirSearchParameterResolver + FtsQueryBuilder for cleaner architecture.
     */
    private SearchQueryResult buildSearchQueries(String resourceType, Map<String, List<String>> criteria) {
        List<SearchQuery> ftsQueries = new ArrayList<>();
        List<String> filters = new ArrayList<>();  // Kept for backward compatibility (unused in FTS/KV architecture)
        
        logger.debug("🔍 buildSearchQueries: Processing {} criteria for {}: {}", criteria.size(), resourceType, criteria);
        
        for (Map.Entry<String, List<String>> entry : criteria.entrySet()) {
            String rawParamName = entry.getKey();
            List<String> values = entry.getValue();
            
            // Step 1: Resolve parameter using new modular resolver
            FhirSearchParameterResolver.ResolvedParameter resolved = 
                searchParameterResolver.resolve(resourceType, rawParamName);
            
            if (resolved == null) {
                logger.warn("🔍 Unknown search parameter: {} for resource type {}", rawParamName, resourceType);
                continue;
            }
            
            // Step 2: Build FTS queries using new modular builder
            List<SearchQuery> paramQueries = ftsQueryBuilder.buildQueries(resolved, values);
            
            if (paramQueries != null && !paramQueries.isEmpty()) {
                ftsQueries.addAll(paramQueries);
                logger.debug("🔍 Added {} queries for parameter {}", paramQueries.size(), rawParamName);
            } else {
                logger.warn("🔍 Failed to build queries for parameter: {}", rawParamName);
            }
        }
        
        logger.debug("🔍 buildSearchQueries: Built {} FTS queries using modular architecture", ftsQueries.size());
        return new SearchQueryResult(ftsQueries, filters);
    }
    
    /**
     * Handle count-only queries for _total=accurate with _count=0
     */
    private Bundle handleCountOnlyQuery(List<SearchQuery> ftsQueries, String resourceType, String bucketName) {
        int totalCount = getAccurateCount(ftsQueries, resourceType, bucketName);
        
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(totalCount);
        
        return bundle;
    }
    
    /**
     * Get accurate count using pure FTS count
     */
    private int getAccurateCount(List<SearchQuery> ftsQueries, String resourceType, String bucketName) {
        try {
            return (int) ftsKvSearchService.getCount(ftsQueries, resourceType);
        } catch (Exception e) {
            logger.error("❌ FTS count query failed for {}: {}", resourceType, e.getMessage());
            // Propagate the error instead of silently returning 0
            throw new InvalidRequestException("Search failed: " + e.getMessage(), e);
        }
    }
    
    // ========== Parameter Parsing Methods (extracted from ResourceProvider) ==========
    
    private SummaryEnum parseSummaryParameter(Map<String, String> searchParams) {
        String summaryValue = searchParams.get("_summary");
        if (summaryValue == null || summaryValue.isEmpty()) {
            return null;
        }
        
        return switch (summaryValue.toLowerCase()) {
            case "true" -> SummaryEnum.TRUE;
            case "false" -> SummaryEnum.FALSE;
            case "text" -> SummaryEnum.TEXT;
            case "data" -> SummaryEnum.DATA;
            case "count" -> SummaryEnum.COUNT;
            default -> SummaryEnum.FALSE;
        };
    }
    
    private Set<String> parseElementsParameter(Map<String, String> searchParams) {
        String elementsValue = searchParams.get("_elements");
        if (elementsValue == null || elementsValue.isEmpty()) {
            return null;
        }
        
        Set<String> elements = new HashSet<>();
        String[] elementArray = elementsValue.split(",");
        
        for (String element : elementArray) {
            String trimmedElement = element.trim();
            if (!trimmedElement.isEmpty() && !trimmedElement.equals("*")) {
                elements.add(trimmedElement);
            }
        }
        
        return elements.isEmpty() ? null : elements;
    }
    
    private int parseCountParameter(Map<String, String> searchParams) {
        String countValue = searchParams.get("_count");
        if (countValue == null || countValue.isEmpty()) {
            return DEFAULT_PAGE_SIZE; // Default page size
        }
        
        try {
            // Trim whitespace (including newlines from URL encoding issues like %0A)
            int count = Integer.parseInt(countValue.trim());
            if (count <= 0) return DEFAULT_PAGE_SIZE;
            
            // Removed mid-level MAX_COUNT_PER_PAGE constraint; rely on pagination + absolute cap below.
            // If needed, future adaptive limits can be based on resource type memory profile.
            
            // Absolute safety cap at 500 even with fastpath (prevents abuse)
            if (count > 500) {
                logger.warn("⚠️ _count={} exceeds absolute limit (500), capping for server protection.", count);
                return 500;
            }
            
            return count;
        } catch (NumberFormatException e) {
            logger.warn("🔍 Invalid _count value '{}', using default {}", countValue, DEFAULT_PAGE_SIZE);
            return DEFAULT_PAGE_SIZE;
        }
    }
    
    private List<SearchSort> parseSortParameter(Map<String, String> searchParams) {
        String sortValue = searchParams.get("_sort");
        if (sortValue == null || sortValue.isEmpty()) {
            // Default sorting by meta.lastUpdated descending when no explicit sort specified
            List<SearchSort> defaultSort = new ArrayList<>();
            defaultSort.add(SearchSort.byField("meta.lastUpdated").desc(true));
            logger.debug("🔍 No explicit sort specified, using default: meta.lastUpdated desc");
            return defaultSort;
        }
        
        List<SearchSort> sortFields = new ArrayList<>();
        String[] fields = sortValue.split(",");
        
        for (String field : fields) {
            field = field.trim();
            if (field.isEmpty()) continue;
            
            boolean isDescending = field.startsWith("-");
            String fieldName = isDescending ? field.substring(1).trim() : field;
            
            if (fieldName.matches("^[a-zA-Z0-9._]+$")) {
                String mappedField = mapFhirFieldToFtsPath(fieldName);
                sortFields.add(SearchSort.byField(mappedField).desc(isDescending));
            }
        }
        
        return sortFields;
    }
    
    private String parseTotalParameter(Map<String, String> searchParams) {
        String totalValue = searchParams.get("_total");
        if (totalValue == null || totalValue.isEmpty()) {
            return "none";
        }
        
        return switch (totalValue.toLowerCase()) {
            case "none", "estimate", "accurate" -> totalValue.toLowerCase();
            default -> "none";
        };
    }
    
    private String mapFhirFieldToFtsPath(String fhirField) {
        // Handle common FHIR field mappings
        return switch (fhirField) {
            case "_lastUpdated", "lastUpdated" -> "meta.lastUpdated";
            case "_id" -> "id";
            case "name" -> "name.family";  // For Patient sorting, use family name
            default -> fhirField;
        };
    }
    
    /**
     * Apply resource filtering based on _summary and _elements parameters
     */
    private Resource applyResourceFiltering(Resource resource, SummaryEnum summaryMode, Set<String> elements) {
        if (summaryMode == null && elements == null) {
            return resource;
        }
        
        try {
            IParser parser = fhirContext.newJsonParser();
            
            if (summaryMode != null) {
                switch (summaryMode) {
                    case TRUE:
                        parser.setSummaryMode(true);
                        break;
                    case COUNT:
                        parser.setEncodeElements(Set.of("id", "resourceType"));
                        break;
                    case TEXT:
                        parser.setEncodeElements(Set.of("id", "resourceType", "text", "meta"));
                        break;
                    case DATA:
                        parser.setOmitResourceId(false);
                        parser.setSummaryMode(false);
                        break;
                    case FALSE:
                        // No summary mode - default behavior
                        break;
                }
            }
            
            if (elements != null && !elements.isEmpty()) {
                Set<String> encodeElements = new HashSet<>();
                encodeElements.add("id");
                encodeElements.add("resourceType");
                encodeElements.add("meta");
                
                String resourceType = resource.fhirType();
                for (String element : elements) {
                    encodeElements.add(resourceType + "." + element);
                }
                
                parser.setEncodeElements(encodeElements);
            }
            
            String filteredJson = parser.encodeResourceToString(resource);
            return parser.parseResource(resource.getClass(), filteredJson);
            
        } catch (Exception e) {
            logger.warn("Failed to apply resource filtering: {}", e.getMessage());
            return resource;
        }
    }
    
    
    /**
     * Execute search using new pagination strategy (FTS gets count+1 keys for pagination detection)
     */
    private Bundle executeSearchWithNewPagination(String resourceType, List<SearchQuery> ftsQueries, int pageSize,
                                                 List<SearchSort> sortFields, SummaryEnum summaryMode, 
                                                 Set<String> elements, String totalMode, String bucketName,
                                                 RequestDetails requestDetails) {
        
    logger.debug("🚀 New Pagination Strategy: {} (page size: {}, fetching count+1 for pagination detection)", resourceType, pageSize);
        
        // Step 1: Execute FTS to get count+1 keys (efficient pagination detection, same as fastpath)
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
            ftsQueries, resourceType, 0, pageSize + 1, sortFields);
        
        List<String> allDocumentKeys = ftsResult.getDocumentKeys();
        long totalCount = ftsResult.getTotalCount();
        
        // Step 2: Determine if pagination is needed
        boolean needsPagination = allDocumentKeys.size() > pageSize;
        
        // Step 3: Get documents for first page
        List<String> firstPageKeys = allDocumentKeys.size() <= pageSize ? 
            allDocumentKeys : 
            allDocumentKeys.subList(0, pageSize);
            
        List<Resource> results = ftsKvSearchService.getDocumentsFromKeys(firstPageKeys, resourceType);
        
        // Step 4: Create pagination state if needed (NEW: Store FTS query, not keys!)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            // Serialize FTS queries for storage
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("regular")
                .resourceType(resourceType)
                .primaryResourceCount((int) totalCount)  // Use FTS totalCount metadata
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(pageSize)  // Next page starts at offset=pageSize
                .primaryPageSize(pageSize)
                .sortFieldsJson(serializedSortFields)
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // NEW query-based approach
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.debug("✅ Created PaginationState: token={}, strategy=query-based, primaryOffset={}, pageSize={}, total={}", 
                       continuationToken, pageSize, pageSize, totalCount);
        }
        
        // Step 5: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal((int) totalCount); // Total from FTS metadata (accurate)
        
        // Add resources to bundle with filtering
    // Base URL no longer includes tenant/bucket segment in single-tenant mode
    String baseUrl = extractBaseUrl(requestDetails, bucketName);
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("📦 Bundle page composition: match={}, include={}, totalEntries={}",
                    results.size(), 0, bundle.getEntry().size());
        }
        
        // Add self link
        bundle.addLink()
                .setRelation("self")
                .setUrl(baseUrl + "/" + resourceType);
        
        // Add next link if pagination is needed
        if (continuationToken != null && needsPagination) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, pageSize, resourceType, bucketName, pageSize, baseUrl));
        }
        
        // Note: No previous link on first page (offset=0)
        
    logger.debug("🚀 New Pagination: Returning {} results, total: {}, pagination: {}", 
                   results.size(), totalCount, needsPagination ? "YES" : "NO");
        return bundle;
    }
    
    /**
     * Handle continuation token for pagination (supports BOTH legacy key-list and new query-based)
     */
    private Bundle handleContinuationTokenNewPagination(String continuationToken, String resourceType,
                                                       int offset, int count,
                                                       SummaryEnum summaryMode, Set<String> elements,
                                                       String bucketName, RequestDetails requestDetails) {
        
    logger.debug("🔑 PAGINATION: Processing continuation token for {}, offset={}, count={}", 
                   resourceType, offset, count);
        
        // Retrieve pagination state (bucketName from method parameter)
        PaginationState paginationState = searchStateManager.getPaginationState(continuationToken, bucketName);
    logger.debug("🔍 DEBUG: handleContinuationTokenNewPagination - state={}", paginationState != null ? "FOUND" : "NULL");
        
        if (paginationState == null) {
            logger.warn("❌ Pagination state not found or expired for token: {}", continuationToken);
            // Return 410 Gone for expired/invalid pagination token (FHIR standard)
            throw new ResourceGoneException("Pagination state has expired or is invalid. Please repeat your original search.");
        }
        
    logger.debug("🔍 DEBUG: State details - useLegacy={}, searchType={}", 
                   paginationState.isUseLegacyKeyList(), paginationState.getSearchType());
        
        // Check if using NEW query-based approach or LEGACY key-list approach
        if (!paginationState.isUseLegacyKeyList()) {
            // NEW APPROACH: Re-execute FTS query with updated offset
            logger.debug("🚀 NEW QUERY-BASED PAGINATION: Re-executing FTS for page offset={}", offset);
            logger.debug("🔍 DEBUG: About to call handleQueryBasedPagination");
            return handleQueryBasedPagination(continuationToken, paginationState, offset, count, summaryMode, elements, requestDetails);
        }
        
        // LEGACY APPROACH: Use stored document keys
        logger.debug("🔑 LEGACY KEY-LIST PAGINATION: Using stored keys");
        List<String> allDocumentKeys = paginationState.getAllDocumentKeys();
        logger.debug("🔍 DEBUG: getAllDocumentKeys returned: {}", allDocumentKeys != null ? "NOT NULL" : "NULL");
        int pageSize = count > 0 ? count : paginationState.getPageSize();
        int fromIndex = Math.min(offset, allDocumentKeys.size());
        int toIndex = Math.min(offset + pageSize, allDocumentKeys.size());
        List<String> currentPageKeys = fromIndex < toIndex ? allDocumentKeys.subList(fromIndex, toIndex) : List.of();
        if (currentPageKeys.isEmpty()) {
            logger.debug("🔑 No more results for pagination token: {}", continuationToken);
            return createEmptyBundle();
        }
        
        logger.debug("🔑 KV-Only: Fetching {} documents for page {}/{}", 
                   currentPageKeys.size(), paginationState.getCurrentPage(), paginationState.getTotalPages());
        
        // For mixed resource types (like _revinclude), we need to group keys by resource type
        // But preserve the original order (primary resources first, then secondary)
        List<Resource> results = new ArrayList<>();
        String searchType = paginationState.getSearchType();
        if ("revinclude".equals(searchType) || "include".equals(searchType)) {
            // Group keys by resource type and retrieve from appropriate collections
            // Filter out any invalid keys (defensive programming)
            Map<String, List<String>> keysByResourceType = currentPageKeys.stream()
                .filter(key -> key != null && key.contains("/"))
                .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
            
            // Fetch resources grouped by type for efficiency
            Map<String, Resource> resourcesByKey = new java.util.HashMap<>();
            for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
                String keyResourceType = entry.getKey();
                List<String> keysForType = entry.getValue();
                logger.debug("🔑 Retrieving {} {} documents", keysForType.size(), keyResourceType);
                List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
                // Store in map for ordering
                for (Resource resource : resourcesForType) {
                    String resourceKey = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
                    resourcesByKey.put(resourceKey, resource);
                }
            }
            
            // Restore original order from currentPageKeys (primary first, then secondary)
            for (String key : currentPageKeys) {
                Resource resource = resourcesByKey.get(key);
                if (resource != null) {
                    results.add(resource);
                }
            }
        } else {
            // Regular pagination - all keys are same resource type
            results = ftsKvSearchService.getDocumentsFromKeys(currentPageKeys, resourceType);
        }
        
        // Note: We do NOT update currentOffset in Couchbase
        // The offset is tracked in the URL (_offset parameter), not in the document
        // This keeps the document immutable (write once, read many) and avoids resetting TTL
        
        // Calculate next offset for "next" link
        int nextOffset = offset + currentPageKeys.size();
        boolean hasMoreResults = nextOffset < allDocumentKeys.size();
        
        // Note: We rely on TTL for cleanup (no explicit delete to avoid unnecessary DB chatter)
        
        // Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // For revinclude/include searches, total should reflect ONLY primary matches, not included resources
        if ("revinclude".equals(searchType) || "include".equals(searchType)) {
            int primaryCount = paginationState.getPrimaryResourceCount();
            bundle.setTotal(primaryCount);
        } else {
            bundle.setTotal(allDocumentKeys.size()); // Total is known from initial FTS for pure primary searches
        }
        
        // Add resources to bundle with filtering
    String baseUrl = paginationState.getBaseUrl(); // stored without bucketName in single-tenant mode
        String primaryResourceType = paginationState.getResourceType();
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String actualResourceType = resource.getResourceType().name();
            
            // Determine search mode: primary resources are "match", secondary are "include"
            Bundle.SearchEntryMode searchMode = actualResourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + actualResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(searchMode);
        }
        if (logger.isDebugEnabled()) {
            int includeCount = (int) results.stream()
                    .filter(r -> !r.getResourceType().name().equals(primaryResourceType))
                    .count();
            int matchCount = results.size() - includeCount;
            logger.debug("📦 Bundle page composition (continuation): match={}, include={}, totalEntries={}",
                    matchCount, includeCount, bundle.getEntry().size());
        }
        
        // Add self link
        bundle.addLink()
                .setRelation("self")
                .setUrl(buildNextPageUrl(continuationToken, offset, resourceType, bucketName, pageSize, baseUrl));
        
        // Add previous link if not on first page
        if (offset > 0) {
            int prevOffset = Math.max(0, offset - pageSize);
            bundle.addLink()
                    .setRelation("previous")
                    .setUrl(buildPreviousPageUrl(continuationToken, prevOffset, resourceType, bucketName, pageSize, baseUrl));
        }
        
        // Add next link if more results available
        if (hasMoreResults) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, nextOffset, resourceType, bucketName, pageSize, baseUrl));
        }
        
        // Calculate current page for logging (1-based)
        int currentPage = (offset / pageSize) + 1;
        int totalPages = (int) Math.ceil((double) allDocumentKeys.size() / pageSize);
        
    logger.debug("🔑 KV-Only Pagination: Returning {} results, page {}/{}, total(primaryOnly)={} (combinedKeys={})", 
                   results.size(), currentPage, totalPages,
                   ("revinclude".equals(searchType) || "include".equals(searchType)) ? paginationState.getPrimaryResourceCount() : allDocumentKeys.size(),
                   allDocumentKeys.size());
        return bundle;
    }
    
    /**
     * Handle query-based pagination (NEW approach) - Re-execute FTS query for each page.
     * This method supports _revinclude, _include, and regular searches.
     * Supports both HAPI and fastpath rendering.
     */
    private Bundle handleQueryBasedPagination(String continuationToken, PaginationState state, int offset, int count,
                                             SummaryEnum summaryMode, Set<String> elements,
                                             RequestDetails requestDetails) {
        logger.debug("🔍 Continuation page: offset={}, count={}, searchType={}", offset, count, state.getSearchType());
        
        // Check if fastpath is enabled (beta: no _summary/_elements support)
        boolean useFastpath = fastpathProperties.isEnabled() && 
                              (summaryMode == null || summaryMode == SummaryEnum.FALSE) && 
                              (elements == null || elements.isEmpty());
        
        String searchType = state.getSearchType();
        String primaryResourceType = state.getResourceType();
        int pageSize = count > 0 ? count : state.getPrimaryPageSize();
        String bucketName = state.getBucketName();
        
        logger.debug("🚀 QUERY-BASED PAGE: type={}, primaryType={}, offset={}, pageSize={}", 
                   searchType, primaryResourceType, offset, pageSize);
        
        // Step 1: Rebuild FTS queries from serialized JSON
        // TODO: For now, use simple approach - rebuild from stored criteria
        // Since we're storing empty query lists, we'll use matchAll for now
        List<SearchQuery> primaryQueries = new ArrayList<>();
        if (state.getPrimaryFtsQueriesJson() != null && !state.getPrimaryFtsQueriesJson().isEmpty()) {
            // TODO: Deserialize queries properly
            logger.warn("⚠️  Query deserialization not yet implemented, using matchAll");
        }
        // For now, use matchAll (works for initial testing)
        // primaryQueries is empty, will use matchAll in buildCombinedSearchQuery
        
        // Step 2: Re-execute FTS for primaries with updated offset (fetch count+1 for pagination detection)
        List<SearchSort> sortFields = deserializeSortFields(state.getSortFieldsJson());
        
        FtsSearchService.FtsSearchResult primaryResult = ftsSearchService.searchForKeys(
            primaryQueries, primaryResourceType, offset, pageSize + 1, sortFields);  // +1 for pagination detection
        
        List<String> primaryKeys = primaryResult.getDocumentKeys();
        logger.debug("🚀 FTS re-execution returned {} primary keys (requested {}+1 for pagination detection)", 
                   primaryKeys.size(), pageSize);
        
        if (primaryKeys.isEmpty()) {
            logger.debug("🚀 No more primaries at offset={}", offset);
            return createEmptyBundle();
        }
        
        // Step 3: Detect pagination and get keys for this page
        boolean hasMorePages = primaryKeys.size() > pageSize;
        List<String> thisPageKeys = hasMorePages ? primaryKeys.subList(0, pageSize) : primaryKeys;
        
        // Step 4: Fetch primary resources (only for this page, not the +1)
        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(thisPageKeys, primaryResourceType);
        
        // Step 4: Handle secondaries based on search type
        List<Resource> allResources = new ArrayList<>(primaryResources);
        
        if ("regular".equals(searchType)) {
            // Regular search has no secondaries - just primaries
            logger.debug("🚀 Regular search continuation: {} primaries only", primaryResources.size());
            
            // For regular search, check if fastpath is enabled
            if (useFastpath) {
                logger.debug("🚀 FASTPATH: Continuation page using fastpath");
                return handleRegularContinuationFastpath(continuationToken, state, offset, thisPageKeys, 
                                                        primaryResourceType, pageSize, bucketName, 
                                                        hasMorePages, requestDetails);
            }
            
        } else if ("revinclude".equals(searchType)) {
            // Re-fetch secondaries for these primaries
            String revIncludeType = state.getRevIncludeResourceType();
            String revIncludeParam = state.getRevIncludeSearchParam();
            
            logger.debug("🚀 Fetching _revinclude secondaries: {} references {}", revIncludeType, revIncludeParam);
            
            // Build reference queries
            List<SearchQuery> refQueries = new ArrayList<>();
            for (String primaryKey : primaryKeys) {
                refQueries.add(SearchQuery.match(primaryKey).field(revIncludeParam + ".reference"));
            }
            
            if (!refQueries.isEmpty()) {
                List<SearchQuery> secondaryQueries = List.of(
                    SearchQuery.disjuncts(refQueries.toArray(new SearchQuery[0]))
                );
                
                int maxSecondaries = state.getMaxBundleSize() - primaryKeys.size();
                FtsSearchService.FtsSearchResult secondaryResult = ftsSearchService.searchForKeys(
                    secondaryQueries, revIncludeType, 0, maxSecondaries, sortFields);
                
                List<String> secondaryKeys = secondaryResult.getDocumentKeys();
                logger.debug("🚀 Fetched {} secondary keys", secondaryKeys.size());
                
                List<Resource> secondaryResources = ftsKvSearchService.getDocumentsFromKeys(secondaryKeys, revIncludeType);
                allResources.addAll(secondaryResources);
            }
            
        } else if ("include".equals(searchType)) {
            // Fetch included resources for this page's primaries
            List<String> includeParamsList = state.getIncludeParamsList();
            if (includeParamsList != null && !includeParamsList.isEmpty()) {
                logger.debug("🚀 Fetching _include secondaries: {} params", includeParamsList.size());
                
                Set<String> includeKeys = new LinkedHashSet<>();
                for (String includeParam : includeParamsList) {
                    // Parse include parameter (e.g., "Encounter:subject")
                    String[] parts = includeParam.split(":");
                    if (parts.length < 2) continue;
                    
                    String paramName = parts[1];  // "subject"
                    List<String> refs = extractReferencesFromResources(primaryResources, paramName);
                    includeKeys.addAll(refs);
                    logger.debug("🚀 _include '{}' produced {} refs", includeParam, refs.size());
                }
                
                if (!includeKeys.isEmpty()) {
                    // Filter out contained references and limit to maxBundleSize
                    int maxIncludes = state.getMaxBundleSize() - primaryKeys.size();
                    List<String> limitedKeys = includeKeys.stream()
                        .filter(ref -> ref != null && ref.contains("/"))
                        .limit(maxIncludes)
                        .collect(Collectors.toList());
                    
                    if (!limitedKeys.isEmpty()) {
                        // Group by type and fetch
                        Map<String, List<String>> includeKeysByType = limitedKeys.stream()
                            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
                        
                        for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                            String includeType = entry.getKey();
                            List<String> keysForType = entry.getValue();
                            List<Resource> includeResources = ftsKvSearchService.getDocumentsFromKeys(keysForType, includeType);
                            allResources.addAll(includeResources);
                        }
                        
                        logger.debug("🚀 Fetched {} included resources ({} contained refs skipped)", 
                                   allResources.size() - primaryResources.size(), includeKeys.size() - limitedKeys.size());
                    } else {
                        logger.debug("🚀 All {} references are contained (skipped)", includeKeys.size());
                    }
                }
            }
            
        } else if ("chain".equals(searchType)) {
            // Chain search continuation - similar to regular search
            logger.debug("🚀 Chain search continuation: {} primaries", primaryResources.size());
            
            // For chain search, check if fastpath is enabled
            if (useFastpath) {
                logger.debug("🚀 FASTPATH: Chain continuation page using fastpath");
                return handleChainContinuationFastpath(continuationToken, state, offset, thisPageKeys, 
                                                      primaryResourceType, pageSize, bucketName, 
                                                      hasMorePages, requestDetails);
            }
            
            // Chain continuation may have _include parameters (stored in state)
            List<String> includeParamsList = state.getIncludeParamsList();
            if (includeParamsList != null && !includeParamsList.isEmpty()) {
                logger.debug("🚀 Fetching _include for chain continuation: {} params", includeParamsList.size());
                
                Set<String> includeKeys = new LinkedHashSet<>();
                for (String includeParam : includeParamsList) {
                    String[] parts = includeParam.split(":");
                    if (parts.length < 2) continue;
                    
                    String paramName = parts[1];
                    List<String> refs = extractReferencesFromResources(primaryResources, paramName);
                    includeKeys.addAll(refs);
                    logger.debug("🚀 _include '{}' produced {} refs", includeParam, refs.size());
                }
                
                if (!includeKeys.isEmpty()) {
                    // Filter out contained references and limit to maxBundleSize
                    int maxIncludes = state.getMaxBundleSize() - primaryKeys.size();
                    List<String> limitedKeys = includeKeys.stream()
                        .filter(ref -> ref != null && ref.contains("/"))
                        .limit(maxIncludes)
                        .collect(Collectors.toList());
                    
                    if (!limitedKeys.isEmpty()) {
                        Map<String, List<String>> includeKeysByType = limitedKeys.stream()
                            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
                        
                        for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                            String includeType = entry.getKey();
                            List<String> keysForType = entry.getValue();
                            List<Resource> includeResources = ftsKvSearchService.getDocumentsFromKeys(keysForType, includeType);
                            allResources.addAll(includeResources);
                        }
                    } else {
                        logger.debug("🚀 All {} references are contained (skipped)", includeKeys.size());
                    }
                    
                    logger.debug("🚀 Fetched {} included resources for chain continuation", allResources.size() - primaryResources.size());
                }
            }
        }
        
        // Step 5: Build Bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // Use stored total from first page (accurate), not re-queried total (may be wrong)
        bundle.setTotal(state.getPrimaryResourceCount());
        
        String baseUrl = state.getBaseUrl();
        for (Resource resource : allResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resType = resource.getResourceType().name();
            boolean isPrimary = resType.equals(primaryResourceType);
            Bundle.SearchEntryMode mode = isPrimary ? Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(mode);
        }
        
        // Step 6: Add pagination links
        int nextOffset = offset + thisPageKeys.size();
        
        // Add self link
        bundle.addLink()
                .setRelation("self")
                .setUrl(buildNextPageUrl(continuationToken, offset, primaryResourceType, bucketName, pageSize, baseUrl));
        
        // Add previous link if not on first page
        if (offset > 0) {
            int prevOffset = Math.max(0, offset - pageSize);
            bundle.addLink()
                    .setRelation("previous")
                    .setUrl(buildPreviousPageUrl(continuationToken, prevOffset, primaryResourceType, bucketName, pageSize, baseUrl));
        }
        
        // Add next link if more results
        if (hasMorePages) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, nextOffset, primaryResourceType, bucketName, pageSize, baseUrl));
        }
        
        logger.debug("🚀 QUERY-BASED PAGE COMPLETE: {} resources ({} primaries + {} secondaries), hasMore={}", 
                   allResources.size(), primaryResources.size(), allResources.size() - primaryResources.size(), hasMorePages);
        
        return bundle;
    }
    
    /**
     * Handle MULTIPLE _revinclude search parameters with FHIR-compliant count+1 pagination strategy.
     * 
     * Key Changes:
     * - _count applies to PRIMARY resources only (FHIR spec compliance)
     * - Fetch count+1 primaries to detect pagination need
     * - Secondaries fetched for EACH _revinclude parameter (respecting bundle cap)
     * - Bundle capped at MAX_BUNDLE_SIZE (500) total resources
     * - Store FTS query (not keys) for lightweight pagination state
     */
    private Bundle handleMultipleRevIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                                List<Include> revIncludes, int count,
                                                SummaryEnum summaryMode, Set<String> elements,
                                                String totalMode, String bucketName, RequestDetails requestDetails) {
        
        logger.debug("🔍 NEW PAGINATION: Handling {} _revinclude parameters for {} (count={}, maxBundle={})", 
                   revIncludes.size(), primaryResourceType, count, MAX_BUNDLE_SIZE);
        
        // If only one _revinclude, delegate to single-revinclude handler
        if (revIncludes.size() == 1) {
            return handleRevIncludeSearch(primaryResourceType, ftsQueries, revIncludes.get(0), count, 
                                        summaryMode, elements, totalMode, bucketName, requestDetails);
        }
        
        // Step 1: FTS query for PRIMARY resources with size=count+1 (to detect pagination need)
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));  // Default sort
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);  // Fetch count+1
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();  // Accurate count from FTS metadata
        
        if (allPrimaryKeys.isEmpty()) {
            logger.debug("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.debug("🔍 PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination need (did we get count+1 results?)
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys;
        
        if (needsPagination) {
            firstPagePrimaryKeys = allPrimaryKeys.subList(0, count);
            logger.debug("🔍 PAGINATION NEEDED: Got {} primaries, truncating to {} for first page", 
                       allPrimaryKeys.size(), count);
        } else {
            firstPagePrimaryKeys = allPrimaryKeys;
            logger.debug("🔍 NO PAGINATION: Got {} primaries (≤ count={})", allPrimaryKeys.size(), count);
        }
        
        // Step 3: Derive primary resource references from keys
        List<String> primaryResourceReferences = new ArrayList<>(firstPagePrimaryKeys.size());
        for (String key : firstPagePrimaryKeys) {
            int slashIdx = key.indexOf('/');
            if (slashIdx > 0 && slashIdx < key.length() - 1) {
                primaryResourceReferences.add(key);
            }
        }
        
        logger.debug("🔍 Derived {} primary references for secondary lookup", primaryResourceReferences.size());
        
        // Step 4: Fetch SECONDARIES for EACH _revinclude parameter (respecting bundle cap)
        List<String> allSecondaryKeys = new ArrayList<>();
        int currentBundleSize = firstPagePrimaryKeys.size();
        
        for (Include revInclude : revIncludes) {
            String revIncludeResourceType = revInclude.getParamType();
            String revIncludeSearchParam = revInclude.getParamName();
            
            int maxSecondariesForThisType = MAX_BUNDLE_SIZE - currentBundleSize;
            if (maxSecondariesForThisType <= 0) {
                logger.debug("🔍 Bundle size cap reached ({}/{}), skipping remaining _revinclude: {}", 
                           currentBundleSize, MAX_BUNDLE_SIZE, revInclude.getValue());
                break;
            }
            
            logger.debug("🔍 Fetching _revinclude: {} -> {} (max={}, currentBundle={})", 
                       primaryResourceType, revInclude.getValue(), maxSecondariesForThisType, currentBundleSize);
            
            // Build FTS query for this secondary type
            List<SearchQuery> referenceQueries = new ArrayList<>();
            for (String primaryReference : primaryResourceReferences) {
                referenceQueries.add(SearchQuery.match(primaryReference).field(revIncludeSearchParam + ".reference"));
            }
            
            if (!referenceQueries.isEmpty()) {
                List<SearchQuery> revIncludeQueries = List.of(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
                
                List<SearchSort> secondarySortFields = new ArrayList<>();
                secondarySortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
                
                FtsSearchService.FtsSearchResult secondaryFtsResult = ftsSearchService.searchForKeys(
                    revIncludeQueries, revIncludeResourceType, 0, maxSecondariesForThisType, secondarySortFields);
                List<String> secondaryKeys = secondaryFtsResult.getDocumentKeys();
                
                logger.debug("🔍 SECONDARY FTS ({}) returned {} keys (limit={}, bundleSize={})", 
                           revIncludeResourceType, secondaryKeys.size(), maxSecondariesForThisType, 
                           currentBundleSize + secondaryKeys.size());
                
                allSecondaryKeys.addAll(secondaryKeys);
                currentBundleSize += secondaryKeys.size();
            }
        }
        
        logger.debug("🔍 First page composition: {} primaries + {} secondaries (from {} _revinclude params) = {} total", 
                   firstPagePrimaryKeys.size(), allSecondaryKeys.size(), revIncludes.size(), currentBundleSize);
        
        // Step 5: KV Batch fetch for all resources
        List<String> firstPageKeys = new ArrayList<>();
        firstPageKeys.addAll(firstPagePrimaryKeys);
        firstPageKeys.addAll(allSecondaryKeys);
        
        // Filter out any invalid keys (defensive programming)
        Map<String, List<String>> keysByResourceType = firstPageKeys.stream()
            .filter(key -> key != null && key.contains("/"))
            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
        
        Map<String, Resource> resourcesByKey = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
            String keyResourceType = entry.getKey();
            List<String> keysForType = entry.getValue();
            logger.debug("🔍 KV Batch fetching {} {} documents", keysForType.size(), keyResourceType);
            List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
            for (Resource resource : resourcesForType) {
                String resourceKey = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
                resourcesByKey.put(resourceKey, resource);
            }
        }
        
        List<Resource> firstPageResources = new ArrayList<>();
        for (String key : firstPageKeys) {
            Resource resource = resourcesByKey.get(key);
            if (resource != null) {
                firstPageResources.add(resource);
            }
        }
        
        logger.debug("🔍 KV Batch completed: {} resources fetched", firstPageResources.size());
        
        // Step 6: Create pagination state if needed (Store ALL _revinclude parameters)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            // For multiple _revinclude, store as comma-separated list
            List<String> revIncludeStrings = revIncludes.stream()
                .map(inc -> inc.getValue())
                .collect(Collectors.toList());
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("revinclude")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) totalPrimaryCount)  // Store accurate total for Bundle.total on continuation pages
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .maxBundleSize(MAX_BUNDLE_SIZE)
                .includeParamsList(revIncludeStrings)  // Store all _revinclude params
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.debug("✅ NEW PAGINATION STATE: token={}, strategy=query-based, revIncludes={}, primaryOffset={}, pageSize={}", 
                       continuationToken, revIncludeStrings.size(), count, count);
        }
        
        // Step 7: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal((int) totalPrimaryCount);  // Total = primaries only
        
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        for (Resource resource : firstPageResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            
            Bundle.SearchEntryMode searchMode = resourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + resource.getIdElement().getIdPart())
                    .getSearch().setMode(searchMode);
        }
        
        // Add pagination links if needed
        if (needsPagination && continuationToken != null) {
            String nextUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                           + "&_offset=" + count + "&_count=" + count;
            bundle.addLink().setRelation("next").setUrl(nextUrl);
        }
                
        logger.debug("🔍 NEW _revinclude COMPLETE: Bundle={} resources ({} primaries + {} secondaries from {} types), total={} primaries, pagination={}, bundleCap={}", 
                   bundle.getEntry().size(), firstPagePrimaryKeys.size(), allSecondaryKeys.size(), 
                   revIncludes.size(), bundle.getTotal(), needsPagination ? "YES" : "NO", MAX_BUNDLE_SIZE);
        
        return bundle;
    }
    
    /**
     * Handle SINGLE _revinclude search with FHIR-compliant count+1 pagination strategy.
     * 
     * Key Changes:
     * - _count applies to PRIMARY resources only (FHIR spec compliance)
     * - Fetch count+1 primaries to detect pagination need
     * - Secondaries fetched per page (not paginated independently)
     * - Bundle capped at MAX_BUNDLE_SIZE (500) total resources
     * - Store FTS query (not keys) for lightweight pagination state
     */
    private Bundle handleRevIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                        Include revInclude, int count,
                                        SummaryEnum summaryMode, Set<String> elements,
                                        String totalMode, String bucketName, RequestDetails requestDetails) {
        
        logger.debug("🔍 NEW PAGINATION: Handling _revinclude search: {} -> {} (count={}, maxBundle={})", 
                   primaryResourceType, revInclude.getValue(), count, MAX_BUNDLE_SIZE);
        
        // Step 1: FTS query for PRIMARY resources with size=count+1 (to detect pagination need)
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));  // Default sort
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);  // Fetch count+1
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();  // Accurate count from FTS metadata
        
        if (allPrimaryKeys.isEmpty()) {
            logger.debug("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.debug("🔍 PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination need (did we get count+1 results?)
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys;
        
        if (needsPagination) {
            // Got more than requested - truncate to count and signal pagination
            firstPagePrimaryKeys = allPrimaryKeys.subList(0, count);
            logger.debug("🔍 PAGINATION NEEDED: Got {} primaries, truncating to {} for first page", 
                       allPrimaryKeys.size(), count);
        } else {
            // Got count or fewer - no pagination needed
            firstPagePrimaryKeys = allPrimaryKeys;
            logger.debug("🔍 NO PAGINATION: Got {} primaries (≤ count={})", allPrimaryKeys.size(), count);
        }
        
        // Step 3: Derive primary resource references from keys (no need to fetch full resources yet)
        List<String> primaryResourceReferences = new ArrayList<>(firstPagePrimaryKeys.size());
        for (String key : firstPagePrimaryKeys) {
            // Keys are already in ResourceType/id format (e.g., "Patient/uuid")
            int slashIdx = key.indexOf('/');
            if (slashIdx > 0 && slashIdx < key.length() - 1) {
                primaryResourceReferences.add(key);
            } else {
                logger.warn("🔍 Unexpected key format for revinclude: {}", key);
            }
        }
        
        logger.debug("🔍 Derived {} primary references for secondary lookup", primaryResourceReferences.size());
        
        // Step 4: Execute FTS query for SECONDARY resources that reference the first page primaries
        String revIncludeResourceType = revInclude.getParamType();  // e.g., "Observation" from "Observation:subject"
        String revIncludeSearchParam = revInclude.getParamName();   // e.g., "subject"
        
        // Build FTS query for secondaries
        List<SearchQuery> revIncludeQueries = new ArrayList<>();
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String primaryReference : primaryResourceReferences) {
            referenceQueries.add(SearchQuery.match(primaryReference).field(revIncludeSearchParam + ".reference"));
        }
        if (!referenceQueries.isEmpty()) {
            revIncludeQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Calculate max secondaries: Bundle cap - primaries
        int maxSecondaries = MAX_BUNDLE_SIZE - firstPagePrimaryKeys.size();
        
        // Fetch secondaries with limit
        List<SearchSort> secondarySortFields = new ArrayList<>();
        secondarySortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
        
        FtsSearchService.FtsSearchResult secondaryFtsResult = ftsSearchService.searchForKeys(
            revIncludeQueries, revIncludeResourceType, 0, maxSecondaries, secondarySortFields);
        List<String> secondaryKeys = secondaryFtsResult.getDocumentKeys();
        
        logger.debug("🔍 SECONDARY FTS returned {} keys (limit={}, bundleSize={}+{}={})", 
                   secondaryKeys.size(), maxSecondaries, firstPagePrimaryKeys.size(), 
                   secondaryKeys.size(), firstPagePrimaryKeys.size() + secondaryKeys.size());
        
        // Step 5: Combine all keys for this page (primary + secondary)
        List<String> firstPageKeys = new ArrayList<>();
        firstPageKeys.addAll(firstPagePrimaryKeys);
        firstPageKeys.addAll(secondaryKeys);
        
        logger.debug("🔍 First page composition: {} primaries + {} secondaries = {} total resources", 
                   firstPagePrimaryKeys.size(), secondaryKeys.size(), firstPageKeys.size());
        
        // Step 6: KV Batch fetch for all resources on this page (grouped by resource type for efficiency)
        // Filter out any invalid keys (defensive programming)
        Map<String, List<String>> keysByResourceType = firstPageKeys.stream()
            .filter(key -> key != null && key.contains("/"))
            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
        
        Map<String, Resource> resourcesByKey = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
            String keyResourceType = entry.getKey();
            List<String> keysForType = entry.getValue();
            logger.debug("🔍 KV Batch fetching {} {} documents", keysForType.size(), keyResourceType);
            List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
            for (Resource resource : resourcesForType) {
                String resourceKey = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
                resourcesByKey.put(resourceKey, resource);
            }
        }
        
        // Restore original order (primary first, then secondary)
        List<Resource> firstPageResources = new ArrayList<>();
        for (String key : firstPageKeys) {
            Resource resource = resourcesByKey.get(key);
            if (resource != null) {
                firstPageResources.add(resource);
            }
        }
        
        logger.debug("🔍 KV Batch completed: {} resources fetched", firstPageResources.size());
        
        // Step 7: Create pagination state if needed (NEW: Store FTS query, not keys!)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            // Serialize FTS queries for storage
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("revinclude")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) totalPrimaryCount)  // Store accurate total for Bundle.total on continuation pages
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)  // Next page starts at offset=count
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .maxBundleSize(MAX_BUNDLE_SIZE)
                .revIncludeResourceType(revIncludeResourceType)
                .revIncludeSearchParam(revIncludeSearchParam)
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // NEW query-based approach
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.debug("✅ NEW PAGINATION STATE: token={}, strategy=query-based, primaryOffset={}, pageSize={}, maxBundle={}", 
                       continuationToken, count, count, MAX_BUNDLE_SIZE);
        }
        
        // Step 8: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // Total must reflect ONLY primary matches (FHIR spec) - use accurate FTS count
        bundle.setTotal((int) totalPrimaryCount);
        
        // Add resources to bundle with appropriate search mode
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        for (Resource resource : firstPageResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            
            // Determine if this is a primary or secondary resource
            Bundle.SearchEntryMode searchMode = resourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(searchMode);
        }
        if (logger.isDebugEnabled()) {
            int pagePrimaryCount = Math.min(allPrimaryKeys.size(), firstPageKeys.size());
            int pageIncludeCount = firstPageKeys.size() - pagePrimaryCount;
            logger.debug("📦 Bundle page composition (_revinclude first page): match={}, include={}, totalEntries={}",
                    pagePrimaryCount, pageIncludeCount, bundle.getEntry().size());
        }
        
        // Add next link if pagination is needed
        if (continuationToken != null && needsPagination) {
            String baseUrlFinal = extractBaseUrl(requestDetails, bucketName);
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count, primaryResourceType, bucketName, count, baseUrlFinal));
        }
        
        logger.debug("🔍 NEW _revinclude COMPLETE: Bundle={} resources ({} primaries + {} secondaries), " +
                   "total={} primaries, pagination={}, bundleCap={}", 
                   firstPageResources.size(), firstPagePrimaryKeys.size(), secondaryKeys.size(),
                   totalPrimaryCount, needsPagination ? "YES" : "NO", MAX_BUNDLE_SIZE);
        return bundle;
    }
    
    /**
     * Handle multiple _include searches with FHIR-compliant count+1 pagination strategy.
     * 
     * Key Changes:
     * - _count applies to PRIMARY resources only (FHIR spec compliance)
     * - Fetch count+1 primaries to detect pagination need
     * - Extract references from ONLY those primaries (not all 1000!)
     * - Fetch included resources (up to bundle limit)
     * - Bundle capped at MAX_BUNDLE_SIZE (500) total resources
     * - Store FTS query (not keys) for lightweight pagination state
     */
    private Bundle handleMultipleIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                              List<Include> includes, int count,
                                              SummaryEnum summaryMode, Set<String> elements,
                                              String totalMode, String bucketName, RequestDetails requestDetails) {
        logger.debug("🔍 NEW PAGINATION: Handling {} _include parameters for {} (count={}, maxBundle={})", 
                   includes.size(), primaryResourceType, count, MAX_BUNDLE_SIZE);

        // Step 1: FTS query for PRIMARY resources with size=count+1 (to detect pagination need)
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));  // Default sort
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);  // Fetch count+1
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();  // Accurate count from FTS metadata
        
        if (allPrimaryKeys.isEmpty()) {
            logger.debug("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.debug("🔍 PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination need (did we get count+1 results?)
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys;
        
        if (needsPagination) {
            // Got more than requested - truncate to count and signal pagination
            firstPagePrimaryKeys = allPrimaryKeys.subList(0, count);
            logger.debug("🔍 PAGINATION NEEDED: Got {} primaries, truncating to {} for first page", 
                       allPrimaryKeys.size(), count);
        } else {
            // Got count or fewer - no pagination needed
            firstPagePrimaryKeys = allPrimaryKeys;
            logger.debug("🔍 NO PAGINATION: Got {} primaries (≤ count={})", allPrimaryKeys.size(), count);
        }

        // Step 3: Fetch ONLY the first page primary resources (optimization!)
        long primFetchStart = System.currentTimeMillis();
        List<Resource> firstPagePrimaryResources = ftsKvSearchService.getDocumentsFromKeys(firstPagePrimaryKeys, primaryResourceType);
        logger.debug("🔍 Fetched {} primary resources in {} ms for first page", 
                firstPagePrimaryResources.size(), System.currentTimeMillis() - primFetchStart);

        // Step 4: Extract _include references from ONLY first page primaries (not all 1000!)
        java.util.LinkedHashSet<String> includeKeySet = new java.util.LinkedHashSet<>();
        int includeParamIndex = 0;
        
        for (Include include : includes) {
            includeParamIndex++;
            String includeValue = include.getValue();
            logger.debug("🔍 Processing _include[{}]: {}", includeParamIndex, includeValue);

            List<String> includeReferences = extractReferencesFromResources(firstPagePrimaryResources, include.getParamName());
            if (includeReferences.isEmpty()) {
                logger.debug("🔍 No references found for _include '{}'", includeValue);
                continue;
            }

            // Collect unique reference keys
            int before = includeKeySet.size();
            for (String ref : includeReferences) {
                if (ref != null && ref.contains("/")) {
                    includeKeySet.add(ref);
                }
            }
            int added = includeKeySet.size() - before;
            logger.debug("🔍 _include '{}' produced {} raw refs, {} unique added (cumulative includeKeys={})", 
                    includeValue, includeReferences.size(), added, includeKeySet.size());
        }
        
        // Calculate max includes: Bundle cap - primaries
        int maxIncludes = MAX_BUNDLE_SIZE - firstPagePrimaryKeys.size();
        List<String> includeKeys = new ArrayList<>(includeKeySet);
        
        // Truncate if necessary
        if (includeKeys.size() > maxIncludes) {
            logger.warn("🔍 Truncating includes from {} to {} (bundle cap)", includeKeys.size(), maxIncludes);
            includeKeys = includeKeys.subList(0, maxIncludes);
        }

        // Filter out contained references (e.g., #medXYZ) - they don't need to be fetched
        List<String> fetchableKeys = includeKeys.stream()
                .filter(ref -> ref != null && ref.contains("/"))
                .collect(Collectors.toList());
        
        logger.debug("🔍 _include extraction complete: {} unique include keys ({} contained refs skipped, limit={})", 
                   fetchableKeys.size(), includeKeys.size() - fetchableKeys.size(), maxIncludes);
        
        // Step 5: Fetch include resources (grouped by resource type)
        Map<String, Resource> includeResourcesByKey = new java.util.HashMap<>();
        
        if (!fetchableKeys.isEmpty()) {
            Map<String, List<String>> includeKeysByType = fetchableKeys.stream()
                    .collect(Collectors.groupingBy(k -> k.substring(0, k.indexOf('/'))));
            
            for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                String includeType = entry.getKey();
                List<String> keysForType = entry.getValue();
                logger.debug("🔍 KV Batch fetching {} {} includes", keysForType.size(), includeType);
                List<Resource> fetched = ftsKvSearchService.getDocumentsFromKeys(keysForType, includeType);
                for (Resource r : fetched) {
                    String k = r.getResourceType().name() + "/" + r.getIdElement().getIdPart();
                    includeResourcesByKey.put(k, r);
                }
            }
        }
        
        logger.debug("🔍 Fetched {} include resources", includeResourcesByKey.size());
        
        // Step 6: Combine all resources for this page (primary + includes)
        List<Resource> firstPageResources = new ArrayList<>();
        firstPageResources.addAll(firstPagePrimaryResources);  // Primaries first
        firstPageResources.addAll(includeResourcesByKey.values());  // Then includes
        
        logger.debug("🔍 First page composition: {} primaries + {} includes = {} total resources", 
                   firstPagePrimaryResources.size(), includeResourcesByKey.size(), firstPageResources.size());

        // Step 7: Create pagination state if needed (NEW: Store FTS query, not keys!)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            // Serialize FTS queries and _include parameters for storage
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            List<String> includeParamsList = includes.stream()
                    .map(Include::getValue)
                    .collect(Collectors.toList());
            
            PaginationState paginationState = PaginationState.builder()
                    .searchType("include")
                    .resourceType(primaryResourceType)
                    .primaryResourceCount((int) totalPrimaryCount)  // Store accurate total for Bundle.total on continuation pages
                    .primaryFtsQueriesJson(serializedQueries)
                    .primaryOffset(count)  // Next page starts at offset=count
                    .primaryPageSize(count)
                    .sortFieldsJson(serializedSortFields)
                    .maxBundleSize(MAX_BUNDLE_SIZE)
                    .includeParamsList(includeParamsList)  // Store _include parameters
                    .bucketName(bucketName)
                    .baseUrl(baseUrl)
                    .useLegacyKeyList(false)  // NEW query-based approach
                    .build();
                    
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.debug("✅ NEW PAGINATION STATE: token={}, strategy=query-based, primaryOffset={}, pageSize={}, includes={}, maxBundle={}",
                    continuationToken, count, count, includes.size(), MAX_BUNDLE_SIZE);
        }

        // Step 8: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // Total must reflect ONLY primary matches (FHIR spec) - use accurate FTS count
        bundle.setTotal((int) totalPrimaryCount);

        // Add resources to bundle with appropriate search mode
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        int primaryCount = firstPagePrimaryResources.size();
        
        for (int i = 0; i < firstPageResources.size(); i++) {
            Resource resource = firstPageResources.get(i);
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resType = resource.getResourceType().name();
            
            // First N resources are primaries, rest are includes
            boolean isPrimary = (i < primaryCount) && resType.equals(primaryResourceType);
            Bundle.SearchEntryMode mode = isPrimary ? Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(mode);
        }

        // Add next link if pagination is needed
        if (continuationToken != null && needsPagination) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count, primaryResourceType, bucketName, count, baseUrl));
        }

        logger.debug("🔍 NEW _include COMPLETE: Bundle={} resources ({} primaries + {} includes), " +
                   "total={} primaries, pagination={}, bundleCap={}", 
                   firstPageResources.size(), firstPagePrimaryResources.size(), includeResourcesByKey.size(),
                   totalPrimaryCount, needsPagination ? "YES" : "NO", MAX_BUNDLE_SIZE);

        return bundle;
    }
    
    /**
     * Handle multiple _include searches with FASTPATH (10× memory reduction).
     * Returns UTF-8 bytes instead of HAPI Bundle object (2x memory savings vs String).
     * 
     * Same pagination logic as handleMultipleIncludeSearch, but skips HAPI parsing.
     */
    private byte[] handleMultipleIncludeSearchFastpath(String primaryResourceType, List<SearchQuery> ftsQueries,
                                              List<Include> includes, int count,
                                              String bucketName, RequestDetails requestDetails) {
        logger.debug("🚀 FASTPATH: Handling {} _include parameters for {} (count={}, maxBundle={})", 
                   includes.size(), primaryResourceType, count, MAX_BUNDLE_SIZE);

        // Step 1: FTS query for PRIMARY resources with size=count+1
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();
        
        if (allPrimaryKeys.isEmpty()) {
            logger.debug("🚀 FASTPATH: No primary resources found, returning empty bundle");
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            String selfUrl = baseUrl + "/" + primaryResourceType;
            byte[] emptyBundle = fastJsonBundleBuilder.buildEmptySearchsetBundle(selfUrl, Instant.now());
            
            // Track response metrics in perfBag
            RequestPerfBagUtils.addCount(requestDetails, "entries", 0);
            RequestPerfBagUtils.addCount(requestDetails, "response_bytes", emptyBundle.length);
            
            return emptyBundle;
        }
        
        logger.debug("🚀 FASTPATH: PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys = needsPagination ? 
            allPrimaryKeys.subList(0, count) : allPrimaryKeys;
        
        logger.debug("🚀 FASTPATH: {} - {} primaries for first page", 
                   needsPagination ? "PAGINATION NEEDED" : "NO PAGINATION", 
                   firstPagePrimaryKeys.size());

        // Step 3: Fetch ONLY first page primary resources as RAW BYTES (ZERO-COPY from Couchbase!)
        long primFetchStart = System.currentTimeMillis();
        Map<String, byte[]> primaryKeyToBytesMap = batchKvService.getDocumentsAsBytesWithKeys(firstPagePrimaryKeys, primaryResourceType);
        logger.debug("🚀 FASTPATH: Fetched {} primary resources as raw bytes with keys in {} ms", 
                primaryKeyToBytesMap.size(), System.currentTimeMillis() - primFetchStart);

        // Step 4: Extract _include references using N1QL (server-side, no double-fetch!)
        // N1QL handles de-duplication and limiting on the server side
        int maxIncludes = MAX_BUNDLE_SIZE - firstPagePrimaryKeys.size();
        List<String> includeReferences = includeReferenceExtractor.extractReferences(
            firstPagePrimaryKeys, includes, primaryResourceType, bucketName, maxIncludes);
        
        // Filter out contained references (e.g., #medXYZ) - they don't need to be fetched
        List<String> includeKeys = includeReferences.stream()
                .filter(ref -> ref != null && ref.contains("/"))
                .collect(Collectors.toList());

        logger.debug("🚀 FASTPATH: _include extraction complete: {} unique include keys ({} contained refs skipped)", 
                   includeKeys.size(), includeReferences.size() - includeKeys.size());
        
        // Step 5: Fetch include resources as RAW BYTES (ZERO-COPY from Couchbase!)
        Map<String, byte[]> includedKeyToBytesMap = new java.util.LinkedHashMap<>();
        
        if (!includeKeys.isEmpty()) {
            Map<String, List<String>> includeKeysByType = includeKeys.stream()
                    .collect(Collectors.groupingBy(k -> k.substring(0, k.indexOf('/'))));
            
            for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                String includeType = entry.getKey();
                List<String> keysForType = entry.getValue();
                logger.debug("🚀 FASTPATH: KV Batch fetching {} {} includes as raw bytes with keys", keysForType.size(), includeType);
                Map<String, byte[]> fetchedMap = batchKvService.getDocumentsAsBytesWithKeys(keysForType, includeType);
                includedKeyToBytesMap.putAll(fetchedMap);
            }
            
            logger.debug("🚀 FASTPATH: Fetched {} include resources as raw bytes with keys", includedKeyToBytesMap.size());
        }
        
        // Step 6: Build Bundle JSON directly (no HAPI parsing!)
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        
        // Build selfUrl with query parameters
        StringBuilder selfUrlBuilder = new StringBuilder(baseUrl + "/" + primaryResourceType + "?");
        selfUrlBuilder.append("_count=").append(count);
        for (Include include : includes) {
            selfUrlBuilder.append("&_include=").append(java.net.URLEncoder.encode(include.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        String selfUrl = selfUrlBuilder.toString();
        
        String nextUrl = null;
        if (needsPagination) {
            // Store pagination state for continuation pages
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            List<String> includeParamsList = includes.stream()
                .map(Include::getValue)
                .collect(Collectors.toList());
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("include")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) totalPrimaryCount)  // Store accurate total
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)  // Next page starts at offset=count
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .maxBundleSize(MAX_BUNDLE_SIZE)
                .includeParamsList(includeParamsList)  // Store _include parameters
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // Query-based approach
                .build();
            
            String continuationToken = searchStateManager.storePaginationState(paginationState);
            nextUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                    + "&_offset=" + count + "&_count=" + count;
            
            logger.debug("🚀 FASTPATH: Pagination enabled, token={}, nextOffset={}", continuationToken, count);
        }
        
        byte[] bundleBytes = fastJsonBundleBuilder.buildSearchsetBundle(
            primaryKeyToBytesMap,
            includedKeyToBytesMap,
            (int) totalPrimaryCount,
            selfUrl,
            nextUrl,
            null,  // No previous link on first page
            baseUrl,
            Instant.now()
        );

        logger.debug("🚀 FASTPATH: _include COMPLETE: Bundle={} resources ({} primaries + {} includes), total={} primaries", 
                   primaryKeyToBytesMap.size() + includedKeyToBytesMap.size(), 
                   primaryKeyToBytesMap.size(), 
                   includedKeyToBytesMap.size(),
                   totalPrimaryCount);

        // Track response metrics in perfBag
        int totalEntries = primaryKeyToBytesMap.size() + includedKeyToBytesMap.size();
        RequestPerfBagUtils.addCount(requestDetails, "entries", totalEntries);
        RequestPerfBagUtils.addCount(requestDetails, "response_bytes", bundleBytes.length);

        return bundleBytes;
    }
    
    /**
     * FASTPATH: Handle regular search (no _include, no _revinclude) with pure JSON assembly
     * Bypasses HAPI parsing/serialization for 10x memory reduction, returns UTF-8 bytes (2x savings vs String)
     */
    private byte[] handleRegularSearchFastpath(String primaryResourceType, List<SearchQuery> ftsQueries,
                                               int count, List<SearchSort> sortFields, String bucketName,
                                               RequestDetails requestDetails) {
        
        logger.debug("🚀 FASTPATH: Handling regular search: {} (count={})", primaryResourceType, count);
        
        // Step 1: Execute FTS to get ALL primary keys (for accurate total count)
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
            ftsQueries,
            primaryResourceType,
            0,          // offset=0 for first page
            count + 1,  // Fetch count+1 to detect pagination
            sortFields
        );
        
        List<String> allPrimaryKeys = ftsResult.getDocumentKeys();
        long actualTotalCount = ftsResult.getTotalCount();  // Accurate total from FTS metadata
        
        boolean needsPagination = allPrimaryKeys.size() > count;
        
        // Step 2: Determine first page keys
        List<String> firstPagePrimaryKeys = needsPagination ? 
            allPrimaryKeys.subList(0, count) : allPrimaryKeys;
        
        logger.debug("🚀 FASTPATH: FTS returned {} keys (pagination: {}), actualTotal={}", 
                   allPrimaryKeys.size(), needsPagination ? "YES" : "NO", actualTotalCount);
        
        // Step 3: Fetch ONLY first page resources as RAW BYTES (ZERO-COPY from Couchbase!)
        long fetchStart = System.currentTimeMillis();
        Map<String, byte[]> primaryKeyToBytesMap = batchKvService.getDocumentsAsBytesWithKeys(
            firstPagePrimaryKeys, primaryResourceType);
        logger.debug("🚀 FASTPATH: Fetched {} resources as raw bytes with keys in {} ms", 
                   primaryKeyToBytesMap.size(), System.currentTimeMillis() - fetchStart);
        
        // Step 4: Build Bundle JSON directly (no HAPI!)
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        
        // Build selfUrl with query parameters
        StringBuilder selfUrlBuilder = new StringBuilder(baseUrl + "/" + primaryResourceType + "?");
        selfUrlBuilder.append("_count=").append(count);
        // TODO: Add other search params from requestDetails
        String selfUrl = selfUrlBuilder.toString();
        
        String nextUrl = null;
        if (needsPagination) {
            // Store pagination state for continuation pages
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("regular")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) actualTotalCount)  // Store accurate total from FTS
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)  // Next page starts at offset=count
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // Query-based approach
                .build();
            
            String continuationToken = searchStateManager.storePaginationState(paginationState);
            nextUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                    + "&_offset=" + count + "&_count=" + count;
            
            logger.debug("🚀 FASTPATH: Pagination enabled, token={}, nextOffset={}", continuationToken, count);
        }
        
        // Build bundle with NO includes (empty map) as UTF-8 bytes
        byte[] bundleBytes = fastJsonBundleBuilder.buildSearchsetBundle(
            primaryKeyToBytesMap,
            new java.util.LinkedHashMap<>(),  // No includes for regular search
            (int) actualTotalCount,  // Always use accurate total from FTS
            selfUrl,
            nextUrl,
            null,  // No previous link on first page
            baseUrl,
            Instant.now()
        );
        
        logger.debug("🚀 FASTPATH: Regular search COMPLETE: Bundle={} resources, total={}", 
                   primaryKeyToBytesMap.size(), actualTotalCount);
        
        // Track response metrics in perfBag
        RequestPerfBagUtils.addCount(requestDetails, "entries", primaryKeyToBytesMap.size());
        RequestPerfBagUtils.addCount(requestDetails, "response_bytes", bundleBytes.length);
        
        return bundleBytes;
    }
    
    /**
     * FASTPATH: Handle regular search continuation page
     * Bypasses HAPI parsing/serialization for 10x memory reduction
     */
    private Bundle handleRegularContinuationFastpath(String continuationToken, PaginationState state, int offset,
                                                     List<String> primaryKeys, String primaryResourceType, 
                                                     int pageSize, String bucketName, boolean hasMorePages,
                                                     RequestDetails requestDetails) {
        
        logger.debug("🚀 FASTPATH: Regular continuation - offset={}, keys={}, hasMore={}", 
                   offset, primaryKeys.size(), hasMorePages);
        
        // Use stored total count (from first page)
        int totalCount = state.getPrimaryResourceCount();
        
        // Fetch as raw bytes (ZERO-COPY from Couchbase!)
        Map<String, byte[]> primaryKeyToBytesMap = batchKvService.getDocumentsAsBytesWithKeys(
            primaryKeys, primaryResourceType);
        
        String baseUrl = state.getBaseUrl();
        
        // Build self URL
        String selfUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                       + "&_offset=" + offset + "&_count=" + pageSize;
        
        // Build next URL if more results
        int nextOffset = offset + primaryKeys.size();
        String nextUrl = hasMorePages ? 
            (baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
             + "&_offset=" + nextOffset + "&_count=" + pageSize) : null;
        
        // Build previous URL if not on first page
        String previousUrl = null;
        if (offset > 0) {
            int prevOffset = Math.max(0, offset - pageSize);
            previousUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                        + "&_offset=" + prevOffset + "&_count=" + pageSize;
        }
        
        // Build JSON bundle as UTF-8 bytes
        byte[] bundleBytes = fastJsonBundleBuilder.buildSearchsetBundle(
            primaryKeyToBytesMap,
            new java.util.LinkedHashMap<>(),  // No includes for regular search
            totalCount,  // Use stored total from first page
            selfUrl,
            nextUrl,
            previousUrl,
            baseUrl,
            Instant.now()
        );
        
        logger.debug("🚀 FASTPATH: Continuation COMPLETE - {} resources, total={}, hasMore={}", 
                   primaryKeyToBytesMap.size(), totalCount, hasMorePages);
        
        // Store UTF-8 bytes in request attribute for interceptor (2x memory savings vs String)
        requestDetails.getUserData().put(FASTPATH_BYTES_ATTRIBUTE, bundleBytes);
        
        // Return empty placeholder Bundle (interceptor will replace with JSON)
        Bundle placeholder = new Bundle();
        placeholder.setType(Bundle.BundleType.SEARCHSET);
        return placeholder;
    }
    
    /**
     * FASTPATH: Handle chain search continuation page with pure JSON assembly
     * Bypasses HAPI parsing/serialization for 10x memory reduction
     */
    private Bundle handleChainContinuationFastpath(String continuationToken, PaginationState state, int offset,
                                                   List<String> primaryKeys, String primaryResourceType, 
                                                   int pageSize, String bucketName, boolean hasMorePages,
                                                   RequestDetails requestDetails) {
        
        logger.debug("🚀 FASTPATH: Chain continuation - offset={}, keys={}, hasMore={}", 
                   offset, primaryKeys.size(), hasMorePages);
        
        // Use stored total count (from first page)
        int totalCount = state.getPrimaryResourceCount();
        
        // Fetch primaries as raw bytes (ZERO-COPY from Couchbase!)
        Map<String, byte[]> primaryKeyToBytesMap = batchKvService.getDocumentsAsBytesWithKeys(
            primaryKeys, primaryResourceType);
        
        // Check for _include parameters
        Map<String, byte[]> includedKeyToBytesMap = new java.util.LinkedHashMap<>();
        List<String> includeParamsList = state.getIncludeParamsList();
        
        if (includeParamsList != null && !includeParamsList.isEmpty()) {
            logger.debug("🚀 FASTPATH: Processing {} _include parameters for chain continuation", includeParamsList.size());
            
            // Convert include param strings to Include objects for N1QL extraction
            List<Include> includeObjects = new ArrayList<>();
            for (String includeParam : includeParamsList) {
                try {
                    includeObjects.add(new Include(includeParam));
                } catch (Exception e) {
                    logger.warn("⚠️  Invalid include parameter: {}", includeParam);
                }
            }
            
            // Extract references using N1QL (server-side, no double-fetch!)
            // N1QL handles de-duplication and limiting on the server side
            int maxIncludes = state.getMaxBundleSize() - primaryKeys.size();
            List<String> refs = includeReferenceExtractor.extractReferences(
                primaryKeys, includeObjects, primaryResourceType, bucketName, maxIncludes);
            
            logger.debug("🚀 FASTPATH: N1QL extracted {} unique references for {} includes", 
                       refs.size(), includeParamsList.size());
            
            // Fetch included resources as JSON with keys
            if (!refs.isEmpty()) {
                // Filter out contained references (e.g., #medXYZ) - they don't need to be fetched
                List<String> limitedKeys = refs.stream()
                    .filter(ref -> ref != null && ref.contains("/"))
                    .collect(Collectors.toList());
                
                if (!limitedKeys.isEmpty()) {
                    Map<String, List<String>> includeKeysByType = limitedKeys.stream()
                        .collect(Collectors.groupingBy(k -> k.substring(0, k.indexOf('/'))));
                    
                    for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                        String includeType = entry.getKey();
                        List<String> keysForType = entry.getValue();
                        Map<String, byte[]> fetchedMap = batchKvService.getDocumentsAsBytesWithKeys(
                            keysForType, includeType);
                        includedKeyToBytesMap.putAll(fetchedMap);
                    }
                    
                    logger.debug("🚀 FASTPATH: Fetched {} included resources as raw bytes with keys ({} contained refs skipped)", 
                               includedKeyToBytesMap.size(), refs.size() - limitedKeys.size());
                } else {
                    logger.debug("🚀 FASTPATH: All {} references are contained (skipped)", refs.size());
                }
            }
        }
        
        String baseUrl = state.getBaseUrl();
        
        // Build self URL
        String selfUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                       + "&_offset=" + offset + "&_count=" + pageSize;
        
        // Build next URL if more results
        int nextOffset = offset + primaryKeys.size();
        String nextUrl = hasMorePages ? 
            (baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
             + "&_offset=" + nextOffset + "&_count=" + pageSize) : null;
        
        // Build previous URL if not on first page
        String previousUrl = null;
        if (offset > 0) {
            int prevOffset = Math.max(0, offset - pageSize);
            previousUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                        + "&_offset=" + prevOffset + "&_count=" + pageSize;
        }
        
        // Build JSON bundle as UTF-8 bytes
        byte[] bundleBytes = fastJsonBundleBuilder.buildSearchsetBundle(
            primaryKeyToBytesMap,
            includedKeyToBytesMap,
            totalCount,  // Use stored total from first page
            selfUrl,
            nextUrl,
            previousUrl,
            baseUrl,
            Instant.now()
        );
        
        logger.debug("🚀 FASTPATH: Chain continuation COMPLETE - {} resources ({} primaries + {} includes), total={}, hasMore={}", 
                   primaryKeyToBytesMap.size() + includedKeyToBytesMap.size(),
                   primaryKeyToBytesMap.size(),
                   includedKeyToBytesMap.size(),
                   totalCount, hasMorePages);
        
        // Store UTF-8 bytes in request attribute for interceptor (2x memory savings vs String)
        requestDetails.getUserData().put(FASTPATH_BYTES_ATTRIBUTE, bundleBytes);
        
        // Return empty placeholder Bundle (interceptor will replace with JSON)
        Bundle placeholder = new Bundle();
        placeholder.setType(Bundle.BundleType.SEARCHSET);
        return placeholder;
    }
    
    /**
     * FASTPATH: Handle multiple _revinclude searches with pure JSON assembly
     * Bypasses HAPI parsing/serialization for 10x memory reduction, returns UTF-8 bytes (2x savings vs String)
     */
    private byte[] handleMultipleRevIncludeSearchFastpath(String primaryResourceType, List<SearchQuery> ftsQueries,
                                                          List<Include> revIncludes, int count,
                                                          String bucketName, RequestDetails requestDetails) {
        
        logger.debug("🚀 FASTPATH: Handling {} _revinclude parameters for {} (count={}, maxBundle={})", 
                   revIncludes.size(), primaryResourceType, count, MAX_BUNDLE_SIZE);
        
        // Step 1: FTS query for PRIMARY resources with size=count+1
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();
        
        if (allPrimaryKeys.isEmpty()) {
            logger.debug("🚀 FASTPATH: No primary resources found, returning empty bundle");
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            String selfUrl = baseUrl + "/" + primaryResourceType;
            byte[] emptyBundle = fastJsonBundleBuilder.buildEmptySearchsetBundle(selfUrl, Instant.now());
            
            // Track response metrics in perfBag
            RequestPerfBagUtils.addCount(requestDetails, "entries", 0);
            RequestPerfBagUtils.addCount(requestDetails, "response_bytes", emptyBundle.length);
            
            return emptyBundle;
        }
        
        logger.debug("🚀 FASTPATH: PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys = needsPagination ? 
            allPrimaryKeys.subList(0, count) : allPrimaryKeys;
        
        logger.debug("🚀 FASTPATH: {} - {} primaries for first page", 
                   needsPagination ? "PAGINATION NEEDED" : "NO PAGINATION", 
                   firstPagePrimaryKeys.size());
        
        // Step 3: Derive primary resource references for secondary lookup
        List<String> primaryResourceReferences = new ArrayList<>(firstPagePrimaryKeys);
        
        // Step 4: Fetch SECONDARIES for EACH _revinclude parameter (respecting bundle cap)
        Map<String, byte[]> allSecondaryKeyToBytesMap = new java.util.LinkedHashMap<>();
        int currentBundleSize = firstPagePrimaryKeys.size();
        
        for (Include revInclude : revIncludes) {
            String revIncludeResourceType = revInclude.getParamType();
            String revIncludeSearchParam = revInclude.getParamName();
            
            int maxSecondariesForThisType = MAX_BUNDLE_SIZE - currentBundleSize;
            if (maxSecondariesForThisType <= 0) {
                logger.debug("🚀 FASTPATH: Bundle size cap reached ({}/{}), skipping remaining _revinclude", 
                           currentBundleSize, MAX_BUNDLE_SIZE);
                break;
            }
            
            logger.debug("🚀 FASTPATH: Fetching _revinclude: {} -> {} (max={}, currentBundle={})", 
                       primaryResourceType, revInclude.getValue(), maxSecondariesForThisType, currentBundleSize);
            
            // Build FTS query for this secondary type
            List<SearchQuery> referenceQueries = new ArrayList<>();
            for (String primaryReference : primaryResourceReferences) {
                referenceQueries.add(SearchQuery.match(primaryReference).field(revIncludeSearchParam + ".reference"));
            }
            
            if (!referenceQueries.isEmpty()) {
                List<SearchQuery> revIncludeQueries = List.of(
                    SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0]))
                );
                
                FtsSearchService.FtsSearchResult secondaryResult = ftsSearchService.searchForKeys(
                    revIncludeQueries, revIncludeResourceType, 0, maxSecondariesForThisType, sortFields);
                List<String> secondaryKeys = secondaryResult.getDocumentKeys();
                
                logger.debug("🚀 FASTPATH: SECONDARY FTS ({}) returned {} keys", 
                           revIncludeResourceType, secondaryKeys.size());
                
                // Fetch secondaries as raw bytes (ZERO-COPY from Couchbase!)
                Map<String, byte[]> secondaryMap = batchKvService.getDocumentsAsBytesWithKeys(
                    secondaryKeys, revIncludeResourceType);
                allSecondaryKeyToBytesMap.putAll(secondaryMap);
                currentBundleSize += secondaryMap.size();
            }
        }
        
        logger.debug("🚀 FASTPATH: First page composition: {} primaries + {} secondaries = {} total", 
                   firstPagePrimaryKeys.size(), allSecondaryKeyToBytesMap.size(), currentBundleSize);
        
        // Step 5: Fetch primary resources as raw bytes (ZERO-COPY from Couchbase!)
        long primFetchStart = System.currentTimeMillis();
        Map<String, byte[]> primaryKeyToBytesMap = batchKvService.getDocumentsAsBytesWithKeys(
            firstPagePrimaryKeys, primaryResourceType);
        logger.debug("🚀 FASTPATH: Fetched {} primary resources as raw bytes with keys in {} ms", 
                   primaryKeyToBytesMap.size(), System.currentTimeMillis() - primFetchStart);
        
        // Step 6: Build Bundle JSON directly
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        
        // Build selfUrl with query parameters
        StringBuilder selfUrlBuilder = new StringBuilder(baseUrl + "/" + primaryResourceType + "?");
        selfUrlBuilder.append("_count=").append(count);
        for (Include revInclude : revIncludes) {
            selfUrlBuilder.append("&_revinclude=").append(java.net.URLEncoder.encode(revInclude.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        String selfUrl = selfUrlBuilder.toString();
        
        String nextUrl = null;
        if (needsPagination) {
            // Store pagination state for continuation pages
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            List<String> revIncludeStrings = revIncludes.stream()
                .map(Include::getValue)
                .collect(Collectors.toList());
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("revinclude")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) totalPrimaryCount)  // Store accurate total
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)  // Next page starts at offset=count
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .maxBundleSize(MAX_BUNDLE_SIZE)
                .includeParamsList(revIncludeStrings)  // Store all _revinclude params
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // Query-based approach
                .build();
            
            String continuationToken = searchStateManager.storePaginationState(paginationState);
            nextUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                    + "&_offset=" + count + "&_count=" + count;
            
            logger.debug("🚀 FASTPATH: Pagination enabled, token={}, nextOffset={}", continuationToken, count);
        }
        
        byte[] bundleBytes = fastJsonBundleBuilder.buildSearchsetBundle(
            primaryKeyToBytesMap,
            allSecondaryKeyToBytesMap,
            (int) totalPrimaryCount,
            selfUrl,
            nextUrl,
            null,  // No previous link on first page
            baseUrl,
            Instant.now()
        );
        
        logger.debug("🚀 FASTPATH: _revinclude COMPLETE: Bundle={} resources ({} primaries + {} secondaries), total={} primaries", 
                   primaryKeyToBytesMap.size() + allSecondaryKeyToBytesMap.size(), 
                   primaryKeyToBytesMap.size(), 
                   allSecondaryKeyToBytesMap.size(),
                   totalPrimaryCount);
        
        // Track response metrics in perfBag
        int totalEntries = primaryKeyToBytesMap.size() + allSecondaryKeyToBytesMap.size();
        RequestPerfBagUtils.addCount(requestDetails, "entries", totalEntries);
        RequestPerfBagUtils.addCount(requestDetails, "response_bytes", bundleBytes.length);
        
        return bundleBytes;
    }
    
    /**
     * Handle chained search with two-query strategy, optionally with _include parameters
     */
    private Bundle handleChainSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                   ChainParam chainParam, List<Include> includes, int count, List<SearchSort> sortFields,
                                   SummaryEnum summaryMode, Set<String> elements,
                                   String totalMode, String bucketName, RequestDetails requestDetails) {

        logger.debug("🔗 Handling chained search: {} with chain {} and {} includes", 
                    primaryResourceType, chainParam, includes.size());

        // Step 1: Execute chain query to find referenced resource IDs
        List<String> referencedResourceIds = executeChainQuery(
            chainParam.getTargetResourceType(),
            chainParam.getSearchParam(),
            chainParam.getValue(),
            bucketName
        );
        
        if (referencedResourceIds.isEmpty()) {
            logger.warn("🔗 No referenced resources found for chain query, returning empty bundle");
            return createEmptyBundle();
        }

        logger.debug("🔗 Chain query found {} referenced {} resources: {}", 
                   referencedResourceIds.size(), chainParam.getTargetResourceType(), referencedResourceIds);
        
        // Check if fastpath is enabled (beta: no _summary/_elements support)
        boolean useFastpath = fastpathProperties.isEnabled() && 
                              (summaryMode == null || summaryMode == SummaryEnum.FALSE) && 
                              (elements == null || elements.isEmpty());
        
        if (useFastpath) {
            logger.debug("🚀 FASTPATH ENABLED: Using JSON fastpath for chain search");
            byte[] resultBytes = handleChainSearchFastpath(primaryResourceType, ftsQueries, chainParam, includes,
                                                         count, sortFields, bucketName, referencedResourceIds, requestDetails);
            
            // Store UTF-8 bytes in request attribute for interceptor (2x memory savings vs String)
            requestDetails.getUserData().put(FASTPATH_BYTES_ATTRIBUTE, resultBytes);
            
            // Return empty placeholder Bundle (interceptor will replace with JSON)
            Bundle placeholder = new Bundle();
            placeholder.setType(Bundle.BundleType.SEARCHSET);
            return placeholder;
        }
        
        // Step 2: Execute primary search with count+1 strategy (efficient pagination detection)
        FtsSearchService.FtsSearchResult chainFtsResult = executePrimaryChainSearchForKeysEfficient(
            primaryResourceType,
            chainParam.getReferenceFieldPath(),
            chainParam.getTargetResourceType(),
            referencedResourceIds,
            ftsQueries, // Additional search criteria
            sortFields,
            count + 1,  // Fetch count+1 for pagination detection
            bucketName
        );
        
        List<String> allPrimaryKeys = chainFtsResult.getDocumentKeys();
        long totalCount = chainFtsResult.getTotalCount();  // Accurate total from FTS metadata
        boolean needsPagination = allPrimaryKeys.size() > count;
        
        logger.debug("🔗 Chain search found {} keys (requested count+1={}, total={}, pagination: {})", 
                   allPrimaryKeys.size(), count + 1, totalCount, needsPagination ? "YES" : "NO");
        
        // Step 3: Get keys for first page
        List<String> firstPageKeys = needsPagination ? allPrimaryKeys.subList(0, count) : allPrimaryKeys;
        
        // Step 4: Fetch first page of resources via KV
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(firstPageKeys, primaryResourceType);
        
        // Step 4: Process _include parameters (if any)
        List<Resource> includedResources = new ArrayList<>();
        if (!includes.isEmpty()) {
            logger.debug("🔗 Processing {} _include parameters for chain search", includes.size());
            
            // Extract references from primary resources for each include
            Set<String> includeKeys = new LinkedHashSet<>();
            for (Include include : includes) {
                String includeValue = include.getValue();
                logger.debug("🔗 Processing _include: {}", includeValue);
                
                List<String> refs = extractReferencesFromResources(primaryResources, include.getParamName());
                if (refs.isEmpty()) {
                    logger.debug("🔗 No references found for _include '{}'", includeValue);
                    continue;
                }
                
                includeKeys.addAll(refs);
                logger.debug("🔗 _include '{}' produced {} refs (cumulative includeKeys={})", 
                           includeValue, refs.size(), includeKeys.size());
            }
            
            // Fetch included resources
            if (!includeKeys.isEmpty()) {
                // Filter out contained references (e.g., #medXYZ) - they don't need to be fetched
                List<String> fetchableKeys = includeKeys.stream()
                    .filter(ref -> ref != null && ref.contains("/"))
                    .collect(Collectors.toList());
                
                if (!fetchableKeys.isEmpty()) {
                    logger.debug("🔗 KV Batch fetching {} includes ({} contained refs skipped)", 
                               fetchableKeys.size(), includeKeys.size() - fetchableKeys.size());
                    Map<String, List<String>> includeKeysByType = fetchableKeys.stream()
                        .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
                    
                    for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                        String includeResourceType = entry.getKey();
                        List<String> keysForType = entry.getValue();
                        List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, includeResourceType);
                        includedResources.addAll(resourcesForType);
                    }
                    
                    logger.debug("🔗 Fetched {} included resources", includedResources.size());
                } else {
                    logger.debug("🔗 All {} references are contained (skipped)", includeKeys.size());
                }
            }
        }
        
        // Step 5: Create pagination state (query-based approach)
        String continuationToken = null;
        
        if (needsPagination) {
            // Serialize queries and chain metadata for re-execution
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            // Store _include parameters if present (for continuation pages)
            List<String> includeParamsList = null;
            if (!includes.isEmpty()) {
                includeParamsList = includes.stream()
                    .map(Include::getValue)
                    .collect(Collectors.toList());
            }
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("chain")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) totalCount)  // Store for Bundle.total
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)  // Next page starts at offset=count
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .includeParamsList(includeParamsList)  // Store _include params for continuation
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // Query-based approach
                .build();
            
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.debug("✅ Created chain PaginationState: token={}, strategy=query-based, offset={}, total={}", 
                       continuationToken, count, totalCount);
        }
        
        // Step 6: Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal((int) totalCount);
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + primaryResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add included resources (search mode = "include")
        for (Resource resource : includedResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add self link
        bundle.addLink()
            .setRelation("self")
            .setUrl(baseUrl + "/" + primaryResourceType + "?" + chainParam.getOriginalParameter() + "=" + chainParam.getValue());
        
        // Add next link if there are more results
        if (continuationToken != null && needsPagination) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken + "&_offset=" + count + "&_count=" + count);
        }
        
        logger.debug("🔗 Chained search complete: {} primaries + {} includes = {} total resources in bundle, totalPrimaries={}, pagination={}", 
                   primaryResources.size(), includedResources.size(), 
                   bundle.getEntry().size(), totalCount, needsPagination ? "YES" : "NO");
        return bundle;
    }


    /**
     * FASTPATH: Handle chained search with pure JSON assembly (10x memory savings)
     * Bypasses HAPI parsing/serialization for both primary and included resources, returns UTF-8 bytes (2x savings vs String)
     */
    private byte[] handleChainSearchFastpath(String primaryResourceType, List<SearchQuery> ftsQueries,
                                            ChainParam chainParam, List<Include> includes, int count,
                                            List<SearchSort> sortFields, String bucketName,
                                            List<String> referencedResourceIds, RequestDetails requestDetails) {
        
        logger.debug("🚀 FASTPATH: Handling chain search: {} with chain {} and {} includes (count={})", 
                   primaryResourceType, chainParam, includes.size(), count);
        
        // Step 1: Execute primary search with count+1 strategy
        FtsSearchService.FtsSearchResult chainFtsResult = executePrimaryChainSearchForKeysEfficient(
            primaryResourceType,
            chainParam.getReferenceFieldPath(),
            chainParam.getTargetResourceType(),
            referencedResourceIds,
            ftsQueries,
            sortFields,
            count + 1,
            bucketName
        );
        
        List<String> allPrimaryKeys = chainFtsResult.getDocumentKeys();
        long totalCount = chainFtsResult.getTotalCount();
        boolean needsPagination = allPrimaryKeys.size() > count;
        
        logger.debug("🚀 FASTPATH: Chain search found {} keys (requested count+1={}, total={}, pagination: {})", 
                   allPrimaryKeys.size(), count + 1, totalCount, needsPagination ? "YES" : "NO");
        
        // Step 2: Get keys for first page
        List<String> firstPageKeys = needsPagination ? allPrimaryKeys.subList(0, count) : allPrimaryKeys;
        
        // Step 3: Fetch primary resources as RAW BYTES (ZERO-COPY from Couchbase!)
        long primFetchStart = System.currentTimeMillis();
        Map<String, byte[]> primaryKeyToBytesMap = batchKvService.getDocumentsAsBytesWithKeys(
            firstPageKeys, primaryResourceType);
        logger.debug("🚀 FASTPATH: Fetched {} primary resources as raw bytes with keys in {} ms", 
                   primaryKeyToBytesMap.size(), System.currentTimeMillis() - primFetchStart);
        
        // Step 4: Process _include parameters (if any) - fetch as RAW BYTES
        Map<String, byte[]> includedKeyToBytesMap = new java.util.LinkedHashMap<>();
        
        if (!includes.isEmpty()) {
            logger.debug("🚀 FASTPATH: Processing {} _include parameters", includes.size());
            
            // Extract references using N1QL (server-side, no double-fetch!)
            // N1QL handles de-duplication and limiting on the server side
            int maxIncludes = MAX_BUNDLE_SIZE - firstPageKeys.size();
            List<String> refs = includeReferenceExtractor.extractReferences(
                firstPageKeys, includes, primaryResourceType, bucketName, maxIncludes);
            
            logger.debug("🚀 FASTPATH: N1QL extracted {} unique references for {} includes", 
                       refs.size(), includes.size());
            
            // Fetch included resources as JSON with keys
            if (!refs.isEmpty()) {
                // Filter out contained references (e.g., #medXYZ) - they don't need to be fetched
                List<String> includeKeys = refs.stream()
                    .filter(ref -> ref != null && ref.contains("/"))
                    .collect(Collectors.toList());
                
                if (!includeKeys.isEmpty()) {
                    logger.debug("🚀 FASTPATH: KV Batch fetching {} includes as JSON ({} contained refs skipped)", 
                               includeKeys.size(), refs.size() - includeKeys.size());
                    Map<String, List<String>> includeKeysByType = includeKeys.stream()
                        .collect(Collectors.groupingBy(k -> k.substring(0, k.indexOf('/'))));
                    
                    for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                        String includeResourceType = entry.getKey();
                        List<String> keysForType = entry.getValue();
                        Map<String, byte[]> fetchedMap = batchKvService.getDocumentsAsBytesWithKeys(
                            keysForType, includeResourceType);
                        includedKeyToBytesMap.putAll(fetchedMap);
                    }
                    
                    logger.debug("🚀 FASTPATH: Fetched {} included resources as raw bytes with keys", includedKeyToBytesMap.size());
                } else {
                    logger.debug("🚀 FASTPATH: All {} references are contained (skipped)", refs.size());
                }
            }
        }
        
        // Step 5: Build Bundle JSON directly
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        
        // Build selfUrl with chain parameter
        String selfUrl = baseUrl + "/" + primaryResourceType + "?" + 
                        chainParam.getOriginalParameter() + "=" + chainParam.getValue() +
                        "&_count=" + count;
        
        String nextUrl = null;
        if (needsPagination) {
            // Store pagination state for continuation pages
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            // Store _include parameters if present (for continuation pages)
            List<String> includeParamsList = null;
            if (!includes.isEmpty()) {
                includeParamsList = includes.stream()
                    .map(Include::getValue)
                    .collect(Collectors.toList());
            }
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("chain")
                .resourceType(primaryResourceType)
                .primaryResourceCount((int) totalCount)
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .includeParamsList(includeParamsList)  // Store _include params for continuation
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)
                .build();
            
            String continuationToken = searchStateManager.storePaginationState(paginationState);
            nextUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                    + "&_offset=" + count + "&_count=" + count;
            
            logger.debug("🚀 FASTPATH: Pagination enabled, token={}, nextOffset={}", continuationToken, count);
        }
        
        byte[] bundleBytes = fastJsonBundleBuilder.buildSearchsetBundle(
            primaryKeyToBytesMap,
            includedKeyToBytesMap,
            (int) totalCount,
            selfUrl,
            nextUrl,
            null,  // No previous link on first page
            baseUrl,
            Instant.now()
        );
        
        logger.debug("🚀 FASTPATH: Chain search COMPLETE: Bundle={} resources ({} primaries + {} includes), total={}, pagination={}", 
                   primaryKeyToBytesMap.size() + includedKeyToBytesMap.size(), 
                   primaryKeyToBytesMap.size(), 
                   includedKeyToBytesMap.size(),
                   totalCount,
                   needsPagination ? "YES" : "NO");
        
        // Track response metrics in perfBag
        int totalEntries = primaryKeyToBytesMap.size() + includedKeyToBytesMap.size();
        RequestPerfBagUtils.addCount(requestDetails, "entries", totalEntries);
        RequestPerfBagUtils.addCount(requestDetails, "response_bytes", bundleBytes.length);
        
        return bundleBytes;
    }



    /**
     * Handle Has search with two-query strategy
     */
    private Bundle handleHasSearch(String resourceType , HasParam hasParam, List<SearchQuery> ftsQueries , String bucketName , List<SearchSort> sortFields , int count , RequestDetails requestDetails){

        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        Map<String, List<String>> chainCriteria = Map.of(hasParam.getCriteriaParam(), List.of(hasParam.getCriteriaValue()));
        SearchQueryResult hasQueryResult = buildSearchQueries(hasParam.getTargetResource(), chainCriteria);
        List<SearchQuery> hasQueries = hasQueryResult.getFtsQueries();
        hasQueries.addAll(ftsQueries);
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(hasQueries, hasParam.getTargetResource(), sortFields);
        List<String> docKeys = new ArrayList<>(ftsResult.getDocumentKeys());
        List<Resource> referencedDocs =  ftsKvSearchService.getDocumentsFromKeys(docKeys ,  hasParam.getTargetResource());


        if (referencedDocs.isEmpty()) {
            logger.warn("🔗 No referenced resources found for has query, returning empty bundle");
            return createEmptyBundle();
        }

        long totalCount = referencedDocs.size();  // Accurate total from FTS metadata
        boolean needsPagination = referencedDocs.size() > count;

        // Step 3: Get keys for first page
        List<Resource> firstPageKeys = needsPagination ? referencedDocs.subList(0, count) : referencedDocs;

        Set<String> results = new HashSet<>();

        for (IBaseResource res : firstPageKeys) {
            String ref = extractSubjectReference(fhirContext, res);
            if (ref != null) {
                results.add(ref);
            }
        }

        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(results.stream().toList(),resourceType);


        String continuationToken = null;

        if (needsPagination) {
            // Serialize queries and chain metadata for re-execution
            List<String> serializedQueries = serializeFtsQueries(hasQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);

            PaginationState paginationState = PaginationState.builder()
                    .searchType("has")
                    .resourceType(resourceType)
                    .primaryResourceCount((int) totalCount)  // Store for Bundle.total
                    .primaryFtsQueriesJson(serializedQueries)
                    .primaryOffset(count)  // Next page starts at offset=count
                    .primaryPageSize(count)
                    .sortFieldsJson(serializedSortFields)
                     .bucketName(bucketName)
                    .baseUrl(baseUrl)
                    .useLegacyKeyList(false)  // Query-based approach
                    .build();

            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ Created HAS PaginationState: token={}, strategy=query-based, offset={}, total={}",
                    continuationToken, count, totalCount);
        }

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal( primaryResources.size());
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, null, null);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }

        bundle.addLink()
                .setRelation("self")
                .setUrl(baseUrl + "/" + resourceType + "?_has" +":"+hasParam.getTargetResource()+":"+ hasParam.getReferenceField()+":"+hasParam.getCriteriaParam() + "=" + hasParam.getCriteriaValue());

        // Add next link if there are more results
        if (continuationToken != null && needsPagination) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(baseUrl + "/" + resourceType + "?_page=" + continuationToken + "&_offset=" + count + "&_count=" + count);
        }

        return  bundle;
    }

    public String extractSubjectReference(FhirContext ctx, IBaseResource resource) {
        RuntimeResourceDefinition def = ctx.getResourceDefinition(resource);
        BaseRuntimeChildDefinition subjectChild = def.getChildByName("subject");
        if (subjectChild == null) return null;

        List<IBase> values = subjectChild.getAccessor().getValues(resource);
        if (values == null || values.isEmpty()) return null;

        if (values.get(0) instanceof org.hl7.fhir.r4.model.Reference ref) {
            return ref.getReference();
        }

        return null;
    }

    /**
     * Handle pagination requests for both legacy and new pagination strategies
     * Supports both HAPI and fastpath rendering
     */
    public Bundle handleRevIncludePagination(String continuationToken, int offset, int count, RequestDetails requestDetails) {
        logger.debug("🔍 Pagination request - token={}, offset={}, count={}", continuationToken, offset, count);
        
        // Get current bucket from tenant context
        String bucketName = com.couchbase.fhir.resources.config.TenantContextHolder.getTenantId();
        
        // First, try the new pagination strategy
        PaginationState paginationState = null;
        try {
            paginationState = searchStateManager.getPaginationState(continuationToken, bucketName);
        } catch (Exception e) {
            logger.error("Failed to retrieve pagination state: {}", e.getMessage(), e);
            throw e;
        }
        
        if (paginationState != null) {
            logger.debug("🔍 DEBUG: paginationState is not null, checking fields...");
            try {
                String type = paginationState.getSearchType();
                logger.debug("🔍 DEBUG: getSearchType() returned: {}", type);
                
                boolean useLegacy = paginationState.isUseLegacyKeyList();
                logger.debug("🔍 DEBUG: isUseLegacyKeyList() returned: {}", useLegacy);
                
                String resType = paginationState.getResourceType();
                logger.debug("🔍 DEBUG: getResourceType() returned: {}", resType);
                
                List<String> allKeys = paginationState.getAllDocumentKeys();
                logger.debug("🔍 DEBUG: getAllDocumentKeys() returned: {}", allKeys != null ? "NOT NULL" : "NULL");
                
                logger.debug("🔍 DEBUG: PaginationState details - type={}, useLegacy={}, resourceType={}", 
                           type, useLegacy, resType);
            } catch (Exception e) {
                logger.error("🔍 DEBUG: Exception accessing paginationState fields: {}", e.getMessage(), e);
                throw e;
            }
            
            logger.debug("🔑 Using new pagination strategy for token: {}", continuationToken);
            // Pass offset and count from URL to handle pagination (document is immutable)
            return handleContinuationTokenNewPagination(continuationToken, paginationState.getResourceType(),
                                                   offset, count,
                                                   SummaryEnum.FALSE, null, // TODO: Store these in PaginationState if needed
                                                   paginationState.getBucketName(), requestDetails);
        }
        
        // No pagination state found - neither new nor legacy
        logger.error("❌ No pagination state found for token: {}", continuationToken);
        throw new ResourceGoneException("Pagination state has expired or is invalid. Please repeat your original search.");
    }
    
    
    // Helper methods for _revinclude implementation
    
    /**
     * Extract full resource references from a list of resources
     */
    private Bundle createEmptyBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }

    /**
     * Detect a missing Couchbase connection by walking the exception cause chain.
     * We use this to suppress noisy stack traces when the cluster has disconnected under load.
     */
    private boolean isNoActiveConnectionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("No active connection found")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
    
    /**
     * Extract base URL from RequestDetails for pagination links
     * Format: https://hostname:port/fhir (protocol and host from config.yaml)
     * 
     * IMPORTANT: Always use the configured base URL from config.yaml to ensure
     * the correct protocol (https vs http) is preserved, especially when behind
     * a reverse proxy that terminates SSL.
     */
    private String extractBaseUrl(RequestDetails requestDetails, String bucketName) {
        // Always use configured base URL to preserve protocol (https vs http)
        // This is critical when behind HAProxy or other reverse proxies that terminate SSL
        String configuredBaseUrl = fhirServerConfig.getNormalizedBaseUrl();
        if (configuredBaseUrl != null && !configuredBaseUrl.equals("http://localhost/fhir")) {
            return configuredBaseUrl;
        }
        
        // Only fall back to request URL if no base URL is configured (development mode)
        if (requestDetails != null) {
            try {
                String completeUrl = requestDetails.getCompleteUrl();
                if (completeUrl != null) {
                    int fhirIndex = completeUrl.indexOf("/fhir");
                    if (fhirIndex != -1) {
                        int endIndex = fhirIndex + 5; // length of /fhir
                        if (endIndex < completeUrl.length()) {
                            char nextChar = completeUrl.charAt(endIndex);
                            if (nextChar == '/' || nextChar == '?') {
                                return completeUrl.substring(0, endIndex);
                            }
                        }
                        return completeUrl.substring(0, endIndex);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to extract base URL from request details: {}", e.getMessage());
            }
        }
        
        // Final fallback
        return configuredBaseUrl != null ? configuredBaseUrl : "http://localhost/fhir";
    }
    
    private String buildNextPageUrl(String continuationToken, int offset, String resourceType, String bucketName, int count, String baseUrl) {
        // Use provided base URL or fall back to configured base
        if (baseUrl == null) {
            baseUrl = fhirServerConfig.getNormalizedBaseUrl() + "/" + bucketName;
        }
        
        // Use different parameter names that HAPI might handle better
        // Use _page instead of _getpages to avoid HAPI validation issues
        return baseUrl + "/" + resourceType + "?_page=" + continuationToken + 
               "&_offset=" + offset + "&_count=" + count;
    }
    
    private String buildPreviousPageUrl(String continuationToken, int offset, String resourceType, String bucketName, int count, String baseUrl) {
        // Use provided base URL or fall back to configured base
        if (baseUrl == null) {
            baseUrl = fhirServerConfig.getNormalizedBaseUrl() + "/" + bucketName;
        }
        
        return baseUrl + "/" + resourceType + "?_page=" + continuationToken + 
               "&_offset=" + offset + "&_count=" + count;
    }
    
    /**
     * Extract full reference strings from resources (for heterogeneous _include)
     */
    private List<String> extractReferencesFromResources(List<Resource> resources, String searchParam) {
        List<String> allReferences = new ArrayList<>();
        
        for (Resource resource : resources) {
            List<String> references = extractReferencesFromResource(resource, searchParam);
            allReferences.addAll(references);
        }
        
        logger.debug("🔍 Extracted {} total reference strings from {} resources", allReferences.size(), resources.size());
        return allReferences;
    }
    
    /**
     * Get resources by their full reference strings (e.g., "Patient/123", "Observation/456")
     * Groups by resource type and does batch KV lookups for each type
     */
    // Removed unused helper methods extractResourceReferences / getResourcesByReferences as include logic now
    // derives references directly and fetches only the subset needed per page.
    
    /**
     * Extract reference values from a resource using the search parameter (handles lists)
     */
    private List<String> extractReferencesFromResource(Resource resource, String searchParam) {
        List<String> references = new ArrayList<>();
        
        logger.debug("🔍 Looking for reference field '{}' in resource {}", searchParam, resource.fhirType());
        
        // Get the HAPI search parameter to resolve the correct field path
        try {
            RuntimeSearchParam hapiSearchParam = fhirContext
                    .getResourceDefinition(resource.fhirType())
                    .getSearchParam(searchParam);
            
            if (hapiSearchParam != null && hapiSearchParam.getPath() != null) {
                String path = hapiSearchParam.getPath();
                logger.debug("🔍 HAPI path for '{}': {}", searchParam, path);
                
                // Use FHIRPathParser to resolve casting expressions
                FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(path);
                String fieldPath = parsed.getPrimaryFieldPath();
                
                if (fieldPath != null) {
                    logger.debug("🔍 Resolved field path: {}", fieldPath);
                    
                    // Navigate through the path and extract all references
                    references.addAll(navigatePathAndExtractReferences(resource, fieldPath));
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 Failed to resolve field path for '{}': {}", searchParam, e.getMessage());
        }
        
        // Log if fallback would be needed (for monitoring purposes)
        if (references.isEmpty()) {
            logger.debug("🔍 No references found for search param '{}' on resource type '{}' - consider updating field mappings", 
                       searchParam, resource.getResourceType());
        }
        
        return references;
    }
    
    /**
     * Navigate through a field path and extract all reference values
     * Handles nested paths like "entry.item" where entry is a list
     */
    private List<String> navigatePathAndExtractReferences(Object currentObject, String fieldPath) {
        List<String> references = new ArrayList<>();
        
        if (currentObject == null || fieldPath == null || fieldPath.isEmpty()) {
            return references;
        }
        
        logger.debug("🔍 Navigating path '{}' on object type {}", fieldPath, currentObject.getClass().getSimpleName());
        
        // Split the path into parts
        String[] pathParts = fieldPath.split("\\.");
        List<Object> currentObjects = new ArrayList<>();
        currentObjects.add(currentObject);
        
        // Navigate through each part of the path
        for (int i = 0; i < pathParts.length; i++) {
            String part = pathParts[i];
            List<Object> nextObjects = new ArrayList<>();
            
            logger.debug("🔍 Processing path part {}/{}: '{}'", i + 1, pathParts.length, part);
            
            for (Object obj : currentObjects) {
                try {
                    // Convert field name to getter method name
                    String getterName = "get" + part.substring(0, 1).toUpperCase() + part.substring(1);
                    // logger.debug("🔍 Calling {} on {}", getterName, obj.getClass().getSimpleName());
                    
                    java.lang.reflect.Method getter = obj.getClass().getMethod(getterName);
                    Object result = getter.invoke(obj);
                    
                    if (result != null) {
                        // Check if result is a list/collection
                        if (result instanceof java.util.List) {
                            java.util.List<?> list = (java.util.List<?>) result;
                            // logger.debug("🔍 Got list with {} items", list.size());
                            nextObjects.addAll(list);
                        } else {
                            // logger.debug("🔍 Got single object: {}", result.getClass().getSimpleName());
                            nextObjects.add(result);
                        }
                    } else {
                        logger.debug("🔍 Getter returned null");
                    }
                } catch (Exception e) {
                    logger.debug("🔍 Error calling getter for '{}': {}", part, e.getMessage());
                }
            }
            
            currentObjects = nextObjects;
            
            if (currentObjects.isEmpty()) {
                logger.debug("🔍 No objects left to process after part '{}'", part);
                break;
            }
        }
        
        // Now extract references from the final objects
        logger.debug("🔍 Have {} final objects to extract references from", currentObjects.size());
        for (Object obj : currentObjects) {
            try {
                // Try to get the reference value - the object should be a Reference type
                if (obj instanceof org.hl7.fhir.r4.model.Reference) {
                    org.hl7.fhir.r4.model.Reference ref = (org.hl7.fhir.r4.model.Reference) obj;
                    String refValue = ref.getReference();
                    if (refValue != null) {
                        // logger.debug("🔍 Extracted reference: {}", refValue);
                        references.add(refValue);
                    }
                } else {
                    logger.debug("🔍 Final object is not a Reference: {}", obj.getClass().getSimpleName());
                }
            } catch (Exception e) {
                logger.debug("🔍 Error extracting reference from {}: {}", obj.getClass().getSimpleName(), e.getMessage());
            }
        }
        
        logger.debug("🔍 Total references extracted: {}", references.size());
        return references;
    }
    
    /**
     * Get resources by their IDs using optimized async batch KV retrieval
     */
    // Removed unused helper getResourcesByIds (replaced by direct getDocumentsFromKeys calls)
    

    
    // ========== Chain Search Helper Methods ==========
    
    /**
     * Execute chain query to find referenced resource IDs
     */
    private List<String> executeChainQuery(String targetResourceType, String searchParam, 
                                         String searchValue, String bucketName) {
        logger.debug("🔗 Executing chain query: {} where {}={}", targetResourceType, searchParam, searchValue);
        
        // Build FTS query for the chain parameter
        Map<String, List<String>> chainCriteria = Map.of(searchParam, List.of(searchValue));
        SearchQueryResult chainQueryResult = buildSearchQueries(targetResourceType, chainCriteria);
        List<SearchQuery> chainQueries = chainQueryResult.getFtsQueries();
        
        // Execute FTS search to get referenced resource IDs
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(chainQueries, targetResourceType, null);
        return ftsResult.getDocumentKeys().stream()
                .map(key -> key.substring(key.lastIndexOf("/") + 1)) // Extract ID from "ResourceType/id"
                .collect(Collectors.toList());
    }
    
    /**
     * Execute primary chain search with count+1 strategy (efficient pagination detection)
     * Only fetches the keys needed for the current page + 1 extra to detect if more pages exist
     * Returns FtsSearchResult with accurate total count from metadata
     */
    private FtsSearchService.FtsSearchResult executePrimaryChainSearchForKeysEfficient(
                                                          String primaryResourceType, String referenceFieldPath,
                                                          String targetResourceType, List<String> referencedIds,
                                                          List<SearchQuery> additionalQueries,
                                                          List<SearchSort> sortFields, int limit, String bucketName) {
        
        logger.debug("🔗 Executing primary chain search for keys: {} where {} references {} IDs (limit={})", 
                   primaryResourceType, referenceFieldPath, targetResourceType, limit);
        
        // Build FTS query for primary resources that reference the found IDs
        List<SearchQuery> primaryQueries = new ArrayList<>();
        
        // Add reference queries (similar to revinclude logic)
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String referencedId : referencedIds) {
            String referenceValue = targetResourceType + "/" + referencedId;
            referenceQueries.add(SearchQuery.match(referenceValue).field(referenceFieldPath));
        }
        
        if (!referenceQueries.isEmpty()) {
            primaryQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Add any additional search criteria
        if (additionalQueries != null) {
            primaryQueries.addAll(additionalQueries);
        }
        
        // Use searchForKeys with limit (count+1 for pagination detection)
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
            primaryQueries, primaryResourceType, 0, limit, sortFields);
        
        logger.debug("🔗 Primary chain search found {} document keys (limit={}), total={}", 
                   ftsResult.getDocumentKeys().size(), limit, ftsResult.getTotalCount());
        return ftsResult;
    }
    
    // ========== FTS Query Serialization Helpers ==========
    
    /**
     * Serialize FTS queries to JSON strings for storage in PaginationState.
     * 
     * @param ftsQueries List of SearchQuery objects
     * @return List of JSON strings (one per query)
     */
    private List<String> serializeFtsQueries(List<SearchQuery> ftsQueries) {
        if (ftsQueries == null || ftsQueries.isEmpty()) {
            return List.of();
        }
        
        List<String> serialized = new ArrayList<>();
        for (SearchQuery query : ftsQueries) {
            try {
                String json = query.export().toString();
                serialized.add(json);
            } catch (Exception e) {
                logger.error("❌ Failed to serialize FTS query: {}", e.getMessage());
                throw new RuntimeException("Failed to serialize FTS query: " + e.getMessage(), e);
            }
        }
        
        return serialized;
    }
    
    /**
     * Deserialize FTS queries from JSON strings stored in PaginationState.
     * 
     * @param queriesJson List of JSON strings
     * @return List of SearchQuery objects
     */
    private List<SearchQuery> deserializeFtsQueries(List<String> queriesJson) {
        if (queriesJson == null || queriesJson.isEmpty()) {
            return List.of();
        }
        
        List<SearchQuery> queries = new ArrayList<>();
        for (String json : queriesJson) {
            try {
                // Parse JSON and rebuild SearchQuery
                // Note: This is a simplification - may need more robust parsing
                SearchQuery query = parseSearchQueryFromJson(json);
                queries.add(query);
            } catch (Exception e) {
                logger.error("❌ Failed to deserialize FTS query from JSON: {}", e.getMessage());
                throw new RuntimeException("Failed to deserialize FTS query: " + e.getMessage(), e);
            }
        }
        
        return queries;
    }
    
    /**
     * Parse SearchQuery from JSON string.
     * This is a temporary implementation - may need to be enhanced based on query complexity.
     */
    private SearchQuery parseSearchQueryFromJson(String json) {
        // For now, we'll store the queries as-is and rebuild them from criteria
        // TODO: Implement proper JSON -> SearchQuery conversion if needed
        // Alternative: Store search criteria instead of serialized queries
        throw new UnsupportedOperationException("Query deserialization not yet implemented - will store criteria instead");
    }
    
    /**
     * Serialize sort fields to simple strings for storage in PaginationState.
     * Format: "fieldName:desc" or "fieldName:asc"
     * 
     * @param sortFields List of SearchSort objects
     * @return List of serialized sort strings
     */
    private List<String> serializeSortFields(List<SearchSort> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return List.of();
        }
        
        // For now, just store default sort indication
        // TODO: Implement proper sort field serialization if complex sorting is needed
        List<String> serialized = new ArrayList<>();
        serialized.add("meta.lastUpdated:desc");  // Default sort
        return serialized;
    }
    
    /**
     * Deserialize sort fields from JSON strings stored in PaginationState.
     * 
     * @param sortFieldsJson List of JSON strings
     * @return List of SearchSort objects
     */
    private List<SearchSort> deserializeSortFields(List<String> sortFieldsJson) {
        if (sortFieldsJson == null || sortFieldsJson.isEmpty()) {
            // Return default sort
            List<SearchSort> defaultSort = new ArrayList<>();
            defaultSort.add(SearchSort.byField("meta.lastUpdated").desc(true));
            return defaultSort;
        }
        
        // TODO: Implement proper JSON -> SearchSort conversion
        // For now, return default sort as fallback
        List<SearchSort> defaultSort = new ArrayList<>();
        defaultSort.add(SearchSort.byField("meta.lastUpdated").desc(true));
        return defaultSort;
    }
}

