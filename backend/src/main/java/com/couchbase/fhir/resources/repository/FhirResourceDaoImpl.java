package com.couchbase.fhir.resources.repository;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.rest.param.DateParam;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.service.CollectionRoutingService;
import com.couchbase.fhir.resources.interceptor.DAOTimingContext;
import com.google.common.base.Stopwatch;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.couchbase.client.java.query.QueryOptions.queryOptions;


public class FhirResourceDaoImpl<T extends IBaseResource> implements  FhirResourceDao<T> {

    private static final Logger logger = LoggerFactory.getLogger(FhirResourceDaoImpl.class);
    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";


    private final ConnectionService connectionService;
    private final FhirContext fhirContext;
    private final CollectionRoutingService collectionRoutingService;

    private final Class<T> resourceClass;

    public FhirResourceDaoImpl(Class<T> resourceClass , ConnectionService connectionService , FhirContext fhirContext, CollectionRoutingService collectionRoutingService) {
        this.resourceClass = resourceClass;
        this.connectionService = connectionService;
        this.fhirContext = fhirContext;
        this.collectionRoutingService = collectionRoutingService;
    }


    @Override
    public Optional<T> read(String resourceType, String id , String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            // Build N1QL query to get specific document using ResourceType::id format
            String documentKey = resourceType + "/" + id;
            String query = collectionRoutingService.buildReadQuery(bucketName, resourceType, documentKey);

            logger.info("READ query: {}", query);
            QueryResult result = cluster.query(query, queryOptions()
                    .parameters(JsonObject.create().put("key", documentKey)));

            if (result.rowsAsObject().isEmpty()) return Optional.empty();

            JsonObject json = result.rowsAsObject().get(0);
            @SuppressWarnings("unchecked")
            T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
            return Optional.of(resource);

        } catch (Exception e) {
            logger.error("Failed to get {}/{}: {}", resourceType, id, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<T> readVersion(String resourceType, String versionId, String id , String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            String documentKey = resourceType + "/" + id + "/"+versionId;

            String query = collectionRoutingService.buildReadVersionQuery(bucketName, resourceType, id , versionId);

            logger.info("READ version query: {}", query);
            QueryResult result = cluster.query(query, queryOptions()
                    .parameters(JsonObject.create().put("key", documentKey)));

            if (result.rowsAsObject().isEmpty()) return Optional.empty();

            JsonObject json = result.rowsAsObject().get(0);
            @SuppressWarnings("unchecked")
            T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
            return Optional.of(resource);

        } catch (Exception e) {
            logger.error("Failed to get {}/{}: {}", resourceType, id, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<T> history(String resourceType, String id, DateParam since , String bucketName) {
        try {
            String connectionName = getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Query all versions from version collection
            String query = collectionRoutingService.buildHistoryQuery(bucketName, resourceType, id , since);


            logger.info("HISTORY query: {}", query);

            QueryResult result = cluster.query(query, queryOptions()
                    .parameters(JsonObject.create()
                            .put("resourceType", resourceType)
                            .put("id", id)));

            List<T> resources = new ArrayList<>();
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            rows.stream()
                    .map(row -> (T) parser.parseResource(row.toString()))
                    .forEach(resources::add);
            return resources;

        } catch (Exception e) {
            logger.error("Failed to get history for {}/{}: {}", resourceType, id, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<T> readMultiple(String resourceType, List<String> ids, String bucketName) {
        List<T> resources = new ArrayList<>();
        
        if (ids == null || ids.isEmpty()) {
            return resources;
        }
        
        try {
            String connectionName = getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            
            // Build document keys with ResourceType::id format
            List<String> documentKeys = new ArrayList<>();
            for (String id : ids) {
                documentKeys.add("'" + resourceType + "/" + id + "'");
            }
            
            // Build N1QL query with USE KEYS array using routing service
            String query = collectionRoutingService.buildReadMultipleQuery(bucketName, resourceType, documentKeys);

            logger.info("READ MULTIPLE query: {}", query);
            QueryResult result = cluster.query(query);

            List<JsonObject> rows = result.rowsAsObject();
            logger.debug("Found {}/{} {} resources", rows.size(), ids.size(), resourceType);
            
            for (JsonObject json : rows) {
                try {
                    @SuppressWarnings("unchecked")
                    T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
                    resources.add(resource);
                } catch (Exception e) {
                    logger.warn("Failed to parse {} resource: {}", resourceType, e.getMessage());
                }
            }
            
            logger.info("Successfully parsed {}/{} {} resources", resources.size(), ids.size(), resourceType);
            return resources;

        } catch (Exception e) {
            logger.error("Failed to get multiple {}: {}", resourceType, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<T> create(String resourceType , T resource , String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            String documentKey = resourceType + "/" + resource.getIdElement().getIdPart();
            System.out.println("document key --- ");
            System.out.println(documentKey);
            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);

            String insertQuery = collectionRoutingService.buildInsertQuery(bucketName, resourceType);

            JsonObject params = JsonObject.create()
                    .put("key", documentKey)
                    .put("value", JsonObject.fromJson(resourceJson));

            QueryResult result = cluster.query(insertQuery, queryOptions().parameters(params));
            logger.info("Successfully created {} with ID: {}", resourceType, documentKey);
            return Optional.of(resource);

        }catch (Exception e){
            logger.error("Failed to create {}: {}", resourceType, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public List<T> search(String resourceType , String query) {
        List<T> resources = new ArrayList<>();
        try {
            Stopwatch stopwatch = Stopwatch.createStarted();
            String connectionName = getDefaultConnection();

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            QueryResult result = cluster.query(query);

            long queryTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            logger.info("Query execution time : " + queryTimeMs + " ms");
            DAOTimingContext.recordQueryTime(queryTimeMs);

            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            rows.stream()  // Use sequential stream to preserve FTS sort order
                    .map(row -> (T) parser.parseResource(row.toString()))
                    .forEach(resources::add);
            
            long totalTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            long parsingTimeMs = totalTimeMs - queryTimeMs;
            logger.debug("Execution time after HAPI parsing result: " + totalTimeMs + " ms");
            DAOTimingContext.recordParsingTime(parsingTimeMs);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resources;
    }
    
    /**
     * Execute count query for _total=accurate operations
     */
    public int getCount(String resourceType, String countQuery) {
        try {
            Stopwatch stopwatch = Stopwatch.createStarted();
            String connectionName = getDefaultConnection();

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            QueryResult result = cluster.query(countQuery);
            logger.info("Count query execution time: " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");

            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            if (rows.isEmpty()) {
                return 0;
            }
            
            // Extract count from first row
            JsonObject firstRow = rows.get(0);
            Object totalObj = firstRow.get("total");
            
            if (totalObj instanceof Number) {
                return ((Number) totalObj).intValue();
            } else if (totalObj instanceof String) {
                return Integer.parseInt((String) totalObj);
            }
            
            return 0;

        } catch (Exception e) {
            logger.error("Failed to execute count query: {}", e.getMessage());
            return 0;
        }
    }

    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}
