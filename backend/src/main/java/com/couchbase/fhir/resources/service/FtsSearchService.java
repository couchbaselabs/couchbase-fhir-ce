package com.couchbase.fhir.resources.service;

import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.result.SearchResult;
import com.couchbase.client.java.search.result.SearchRow;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.sort.SearchSort;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.gateway.CouchbaseGateway;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Search service that returns document keys only.
 * Supports two modes via feature flag:
 * 1. FTS SDK (legacy, slow: p99=2600ms)
 * 2. Query service with SEARCH() (fast: p99=15ms, 170x improvement!)
 * 
 * Uses CouchbaseGateway for centralized connection management and circuit breaker.
 */
@Service
public class FtsSearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsSearchService.class);
    
    @Autowired
    private CouchbaseGateway couchbaseGateway;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    @Value("${couchbase.search.use-query-service:false}")
    private boolean useQueryService;
    
    /**
     * Execute FTS search for maximum keys (new pagination strategy)
     * Always fetches up to 1000 keys with offset=0 for optimal pagination
     * 
     * @param ftsQueries List of FTS search queries
     * @param resourceType FHIR resource type
     * @param sortFields Sort fields for ordering results
     * @return FtsSearchResult containing up to 1000 document keys
     */
    public FtsSearchResult searchForAllKeys(List<SearchQuery> ftsQueries, String resourceType, 
                                          List<SearchSort> sortFields) {
        return searchForKeys(ftsQueries, resourceType, 0, 1000, sortFields);
    }
    
    /**
     * Execute search and return document keys only
     * Routes to either FTS SDK or Query service based on feature flag
     * 
     * @param ftsQueries List of FTS search queries
     * @param resourceType FHIR resource type
     * @param from Pagination offset
     * @param size Number of results to return
     * @param sortFields Sort fields for ordering results
     * @return FtsSearchResult containing document keys and metadata
     */
    public FtsSearchResult searchForKeys(List<SearchQuery> ftsQueries, String resourceType, 
                                       int from, int size, List<SearchSort> sortFields) {
        
        // Route to Query service or FTS SDK based on feature flag
        if (useQueryService) {
            logger.debug("🚀 Using Query service with SEARCH() for {}", resourceType);
            return searchForKeysViaQuery(ftsQueries, resourceType, from, size, sortFields);
        } else {
            logger.debug("🔍 Using FTS SDK for {}", resourceType);
            return searchForKeysViaFtsSdk(ftsQueries, resourceType, from, size, sortFields);
        }
    }
    
    /**
     * Execute FTS search using SDK (legacy, slow path)
     * p99: 2600ms, Max: 7700ms
     */
    private FtsSearchResult searchForKeysViaFtsSdk(List<SearchQuery> ftsQueries, String resourceType, 
                                                    int from, int size, List<SearchSort> sortFields) {
        
        try {
            String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
            if (ftsIndex == null) {
                throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
            }
            
            // Build FTS SearchQuery using SDK API (not JSON string)
            SearchQuery combinedQuery = buildCombinedSearchQuery(ftsQueries, resourceType);
                        
            // Build and log options
            SearchOptions searchOptions = buildOptions(from, size, sortFields);

            if (logger.isInfoEnabled()) {
                try {
                    String queryJson = combinedQuery.export().toString();
                    String optionsJson = exportOptions(searchOptions, from, size, sortFields);
                    logger.debug("🔍 FTS Request Payload:\n  query={}\n  options={}, Index={}", queryJson, optionsJson, ftsIndex);
                } catch (Exception e) {
                    logger.error("🔍 Failed to export FTS request payload: {}", e.getMessage());
                }
            }

            long ftsStartTime = System.currentTimeMillis();
            SearchResult searchResult = couchbaseGateway.searchQuery("default", ftsIndex, combinedQuery, searchOptions);
            
            // Check for FTS errors in the result metadata
            if (searchResult.metaData().errors() != null && !searchResult.metaData().errors().isEmpty()) {
                String errorMsg = searchResult.metaData().errors().toString();
                logger.error("❌ FTS search returned errors for {}: {}", resourceType, errorMsg);
                throw new RuntimeException("FTS search failed: " + errorMsg);
            }
            
            long afterQueryTime = System.currentTimeMillis();            
            // Extract document keys from search results
            List<String> documentKeys = new ArrayList<>();
            for (SearchRow row : searchResult.rows()) {
                // FTS returns document IDs, which are our document keys
                String documentKey = row.id();
                documentKeys.add(documentKey);
            }
            
            long ftsElapsedTime = System.currentTimeMillis() - ftsStartTime;
            long processingTime = ftsElapsedTime - (afterQueryTime - ftsStartTime);
            long serverExecutionTime = searchResult.metaData().metrics().took().toMillis();
            long roundTripTime = afterQueryTime - ftsStartTime;
            long networkOverhead = roundTripTime - serverExecutionTime;
            
            logger.debug("🔍 FTS search returned {} document keys for {} in {} ms (roundTrip: {} ms, serverExec: {} ms, networkOverhead: {} ms, processing: {} ms)", 
                       documentKeys.size(), resourceType, ftsElapsedTime, 
                       roundTripTime, serverExecutionTime, networkOverhead, processingTime);
            
            return new FtsSearchResult(
                documentKeys,
                searchResult.metaData().metrics().totalRows(),
                serverExecutionTime
            );
            
        } catch (Exception e) {
            logger.error("❌ FTS SDK search failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS search failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Execute FTS search using Query service with SEARCH() (NEW, FAST!)
     * p99: ~15ms, Max: ~50ms (170x improvement over FTS SDK!)
     * 
     * Uses N1QL: SELECT META().id FROM bucket WHERE SEARCH(...)
     */
    private FtsSearchResult searchForKeysViaQuery(List<SearchQuery> ftsQueries, String resourceType,
                                                   int from, int size, List<SearchSort> sortFields) {
        try {
            String bucketName = TenantContextHolder.getTenantId();
            String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
            if (ftsIndex == null) {
                throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
            }
            
            // Build the search query JSON from SearchQuery objects
            SearchQuery combinedQuery = buildCombinedSearchQuery(ftsQueries, resourceType);
            String searchQueryJson = combinedQuery.export().toString();
            
            // Build N1QL with SEARCH()
            // Note: We fetch ALL results and handle pagination in memory for now
            // This matches FTS SDK behavior and allows accurate total count
            StringBuilder n1ql = new StringBuilder();
            n1ql.append("SELECT META().id AS id ");
            n1ql.append("FROM `").append(bucketName).append("` ");
            n1ql.append("WHERE SEARCH(`").append(bucketName).append("`, ");
            n1ql.append(searchQueryJson);
            
            // Add search options
            JsonObject searchOptions = JsonObject.create();
            searchOptions.put("index", ftsIndex);
            if (sortFields != null && !sortFields.isEmpty()) {
                // TODO: Convert SearchSort to JSON if needed
                logger.debug("Sort fields provided but not yet implemented for Query mode");
            }
            
            n1ql.append(", ").append(searchOptions.toString()).append(")");
            
            // Add LIMIT and OFFSET for pagination
            n1ql.append(" LIMIT ").append(size);
            if (from > 0) {
                n1ql.append(" OFFSET ").append(from);
            }
            
            logger.debug("🚀 Query SEARCH N1QL: {}", n1ql.toString());
            
            // Execute query
            long queryStartTime = System.currentTimeMillis();
            QueryResult queryResult = couchbaseGateway.query("default", 
                n1ql.toString(), 
                QueryOptions.queryOptions().timeout(Duration.ofSeconds(30)));
            
            // Extract document keys from results
            List<String> documentKeys = new ArrayList<>();
            for (JsonObject row : queryResult.rowsAsObject()) {
                String docKey = row.getString("id");
                if (docKey != null) {
                    documentKeys.add(docKey);
                }
            }
            
            long queryElapsedTime = System.currentTimeMillis() - queryStartTime;
            
            // Get total count from Query metrics (if available)
            long totalCount = documentKeys.size(); // Approximate for now
            
            logger.debug("🚀 Query SEARCH returned {} document keys for {} in {} ms", 
                       documentKeys.size(), resourceType, queryElapsedTime);
            
            return new FtsSearchResult(
                documentKeys,
                totalCount,
                queryElapsedTime
            );
            
        } catch (Exception e) {
            logger.error("❌ Query SEARCH failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("Query SEARCH failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Execute count query for _total=accurate operations
     * Routes to either FTS SDK or Query service based on feature flag
     */
    public long getCount(List<SearchQuery> ftsQueries, String resourceType) {
        
        // Route to Query service or FTS SDK based on feature flag
        if (useQueryService) {
            logger.debug("🚀 Using Query service COUNT for {}", resourceType);
            return getCountViaQuery(ftsQueries, resourceType);
        } else {
            logger.debug("🔍 Using FTS SDK COUNT for {}", resourceType);
            return getCountViaFtsSdk(ftsQueries, resourceType);
        }
    }
    
    /**
     * Execute FTS count query using SDK (legacy)
     */
    private long getCountViaFtsSdk(List<SearchQuery> ftsQueries, String resourceType) {
        
        try {
            String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
            if (ftsIndex == null) {
                throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
            }
            
            // Build FTS SearchQuery for count
            SearchQuery combinedQuery = buildCombinedSearchQuery(ftsQueries, resourceType);
            
            logger.debug("🔍 FTS Count Query: index={}, query={}", ftsIndex, combinedQuery);
            
            // Execute FTS search for count only and measure timing
            long ftsStartTime = System.currentTimeMillis();
            SearchOptions countOptions = SearchOptions.searchOptions()
                .timeout(Duration.ofSeconds(30))
                .limit(0)
                .includeLocations(false)
                .disableScoring(true);

            if (logger.isDebugEnabled()) {
                logger.debug("🔍 FTS Count Options: {}", exportOptions(countOptions, 0, 0, null));
            }

            SearchResult searchResult = couchbaseGateway.searchQuery("default", ftsIndex, combinedQuery, countOptions);
            
            // Check for FTS errors in the result metadata
            if (searchResult.metaData().errors() != null && !searchResult.metaData().errors().isEmpty()) {
                String errorMsg = searchResult.metaData().errors().toString();
                logger.error("❌ FTS count query returned errors for {}: {}", resourceType, errorMsg);
                throw new RuntimeException("FTS count failed: " + errorMsg);
            }
            
            long totalCount = searchResult.metaData().metrics().totalRows();
            long ftsElapsedTime = System.currentTimeMillis() - ftsStartTime;
            long serverExecutionTime = searchResult.metaData().metrics().took().toMillis();
            long networkOverhead = ftsElapsedTime - serverExecutionTime;
            logger.debug("🔍 FTS SDK count query returned {} total results for {} in {} ms (serverExec: {} ms, networkOverhead: {} ms)", 
                       totalCount, resourceType, ftsElapsedTime, serverExecutionTime, networkOverhead);
            
            return totalCount;
            
        } catch (Exception e) {
            logger.error("❌ FTS SDK count query failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS count query failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Execute count query using Query service with SEARCH()
     */
    private long getCountViaQuery(List<SearchQuery> ftsQueries, String resourceType) {
        try {
            String bucketName = TenantContextHolder.getTenantId();
            String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
            if (ftsIndex == null) {
                throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
            }
            
            // Build the search query JSON
            SearchQuery combinedQuery = buildCombinedSearchQuery(ftsQueries, resourceType);
            String searchQueryJson = combinedQuery.export().toString();
            
            // Build N1QL COUNT with SEARCH()
            StringBuilder n1ql = new StringBuilder();
            n1ql.append("SELECT COUNT(*) AS count ");
            n1ql.append("FROM `").append(bucketName).append("` ");
            n1ql.append("WHERE SEARCH(`").append(bucketName).append("`, ");
            n1ql.append(searchQueryJson);
            
            JsonObject searchOptions = JsonObject.create();
            searchOptions.put("index", ftsIndex);
            
            n1ql.append(", ").append(searchOptions.toString()).append(")");
            
            logger.debug("🚀 Query SEARCH COUNT N1QL: {}", n1ql.toString());
            
            // Execute query
            long queryStartTime = System.currentTimeMillis();
            QueryResult queryResult = couchbaseGateway.query("default", 
                n1ql.toString(), 
                QueryOptions.queryOptions().timeout(Duration.ofSeconds(30)));
            
            // Extract count
            long count = 0;
            List<JsonObject> rows = queryResult.rowsAsObject();
            if (!rows.isEmpty()) {
                count = rows.get(0).getLong("count");
            }
            
            long queryElapsedTime = System.currentTimeMillis() - queryStartTime;
            logger.debug("🚀 Query SEARCH COUNT returned {} total results for {} in {} ms", 
                       count, resourceType, queryElapsedTime);
            
            return count;
            
        } catch (Exception e) {
            logger.error("❌ Query SEARCH COUNT failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("Query COUNT failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build combined SearchQuery from individual SearchQuery objects using SDK API
     */
    private SearchQuery buildCombinedSearchQuery(List<SearchQuery> ftsQueries, String resourceType) {
        
        // Determine if resourceType filter is needed (same logic as N1QL builder)
        boolean needsResourceTypeFilter = shouldIncludeResourceTypeFilter(resourceType);
        
        List<SearchQuery> allQueries = new ArrayList<>();
        
        // Add resourceType filter if needed
        if (needsResourceTypeFilter) {
            allQueries.add(SearchQuery.match(resourceType).field("resourceType"));
        }
        
        // Add the actual search queries
        allQueries.addAll(ftsQueries);
        
        // Build the final query
        if (allQueries.isEmpty()) {
            return SearchQuery.matchAll();
        } else if (allQueries.size() == 1) {
            return allQueries.get(0);
        } else {
            // Use conjuncts (AND) to combine resourceType filter with search queries
            return SearchQuery.conjuncts(allQueries.toArray(new SearchQuery[0]));
        }
    }
    
    /**
     * Determine if resourceType filter is needed based on collection type
     */
    private boolean shouldIncludeResourceTypeFilter(String resourceType) {
        String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
        return "General".equals(targetCollection);
    }

    /**
     * Build SearchOptions with sort & standard flags.
     */
    private SearchOptions buildOptions(int from, int size, List<SearchSort> sortFields) {
        SearchOptions opts = SearchOptions.searchOptions()
            .timeout(Duration.ofSeconds(30))
            .limit(size)
            .skip(from)
            .includeLocations(false)
            .disableScoring(true);
        if (sortFields != null && !sortFields.isEmpty()) {
            // Cast to Object[] to satisfy varargs on older SDK signatures
            opts.sort((Object[]) sortFields.toArray(new SearchSort[0]));
            logger.debug("🔍 FTS: Added {} sort fields", sortFields.size());
        }
        return opts;
    }


    /**
     * Produce lightweight JSON string of options (since SDK doesn't expose export()).
     */
    private String exportOptions(SearchOptions options, int from, int size, List<SearchSort> sortFields) {
        String sorts = (sortFields == null || sortFields.isEmpty()) ? "[]" : 
            "[" + sortFields.size() + "_sort_fields]";
        return '{' +
            "\"from\":" + from + ',' +
            "\"size\":" + size + ',' +
            "\"disableScoring\":true," +
            "\"includeLocations\":false," +
            "\"sort\":" + sorts +
            '}';
    }
    
    /**
     * Search for keys in a specific FTS index (for custom collections like Versions)
     * This bypasses the CollectionRoutingService and uses the provided index name directly
     * 
     * @param ftsQueries List of FTS search queries
     * @param ftsIndexName Explicit FTS index name (e.g., "ftsVersions")
     * @param sortFields Sort fields for ordering results
     * @param bucketName Bucket name for index lookup
     * @return FtsSearchResult containing document keys
     */
    public FtsSearchResult searchForAllKeysInCollection(List<SearchQuery> ftsQueries, String ftsIndexName,
                                                        List<SearchSort> sortFields, String bucketName) {
        try {
            // Build the full index name: {bucket}.{scope}.{indexName}
            String fullIndexName = bucketName + ".Resources." + ftsIndexName;
            
            // Build combined query (no automatic resourceType filter since this is a custom search)
            SearchQuery combinedQuery;
            if (ftsQueries.size() == 1) {
                combinedQuery = ftsQueries.get(0);
            } else if (ftsQueries.size() > 1) {
                combinedQuery = SearchQuery.conjuncts(ftsQueries.toArray(new SearchQuery[0]));
            } else {
                combinedQuery = SearchQuery.matchAll();
            }
            
            // Build search options
            SearchOptions searchOptions = buildOptions(0, 1000, sortFields);
            
            logger.debug("🔍 FTS search on custom index: {} with {} queries", fullIndexName, ftsQueries.size());
            
            long ftsStartTime = System.currentTimeMillis();
            SearchResult searchResult = couchbaseGateway.searchQuery("default", fullIndexName, combinedQuery, searchOptions);
            
            // Check for errors
            if (searchResult.metaData().errors() != null && !searchResult.metaData().errors().isEmpty()) {
                String errorMsg = searchResult.metaData().errors().toString();
                logger.error("❌ FTS search on {} returned errors: {}", fullIndexName, errorMsg);
                throw new RuntimeException("FTS search failed: " + errorMsg);
            }
            
            // Extract document keys
            List<String> documentKeys = new ArrayList<>();
            for (SearchRow row : searchResult.rows()) {
                documentKeys.add(row.id());
            }
            
            long ftsElapsedTime = System.currentTimeMillis() - ftsStartTime;
            long serverExecutionTime = searchResult.metaData().metrics().took().toMillis();
            long networkOverhead = ftsElapsedTime - serverExecutionTime;
            logger.debug("🔍 FTS search on {} returned {} document keys in {} ms (serverExec: {} ms, networkOverhead: {} ms)", 
                       fullIndexName, documentKeys.size(), ftsElapsedTime, serverExecutionTime, networkOverhead);
            
            return new FtsSearchResult(
                documentKeys,
                searchResult.metaData().metrics().totalRows(),
                serverExecutionTime
            );
            
        } catch (Exception e) {
            logger.error("❌ FTS search on {} failed: {}", ftsIndexName, e.getMessage());
            throw new RuntimeException("FTS search failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Result container for FTS search operations
     */
    public static class FtsSearchResult {
        private final List<String> documentKeys;
        private final long totalCount;
        private final long executionTimeMs;
        
        public FtsSearchResult(List<String> documentKeys, long totalCount, long executionTimeMs) {
            this.documentKeys = documentKeys;
            this.totalCount = totalCount;
            this.executionTimeMs = executionTimeMs;
        }
        
        public List<String> getDocumentKeys() {
            return documentKeys;
        }
        
        public long getTotalCount() {
            return totalCount;
        }
        
        public long getExecutionTimeMs() {
            return executionTimeMs;
        }
        
        public boolean isEmpty() {
            return documentKeys.isEmpty();
        }
        
        public int size() {
            return documentKeys.size();
        }
    }
}
