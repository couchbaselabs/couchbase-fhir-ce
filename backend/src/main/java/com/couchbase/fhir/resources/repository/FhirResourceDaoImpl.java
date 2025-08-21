package com.couchbase.fhir.resources.repository;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.transactions.TransactionGetResult;
import com.couchbase.client.java.transactions.TransactionResult;
import com.couchbase.client.java.transactions.Transactions;
import com.couchbase.client.java.transactions.error.TransactionCommitAmbiguousException;
import com.couchbase.client.java.transactions.error.TransactionFailedException;
import com.couchbase.fhir.resources.model.VersionedResource;
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

    private final Class<T> resourceClass;

    public FhirResourceDaoImpl(Class<T> resourceClass , ConnectionService connectionService , FhirContext fhirContext) {
        this.resourceClass = resourceClass;
        this.connectionService = connectionService;
        this.fhirContext = fhirContext;
    }


    @Override
    public JsonObject read(String resourceType, String id , String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            // Build N1QL query to get specific document using ResourceType::id format
            String documentKey = resourceType + "/" + id;
            String query = String.format(
                    "SELECT c.* " +
                            "FROM `%s`.`%s`.`%s` c " +
                            "USE KEYS '%s'",
                    bucketName, DEFAULT_SCOPE, resourceType, documentKey
            );

            QueryResult result = cluster.query(query, queryOptions()
                    .parameters(JsonObject.create().put("key", documentKey)));

            if (result.rowsAsObject().isEmpty()) return JsonObject.create();

            JsonObject json = result.rowsAsObject().get(0);
            return json;
            /*@SuppressWarnings("unchecked")
            T resource = (T) fhirContext.newJsonParser().parseResource(json.toString());
            return Optional.of(resource);*/

        } catch (Exception e) {
            logger.error("Failed to get {}/{}: {}", resourceType, id, e.getMessage());
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

            String insertQuery = String.format(
                    "INSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ($key, $value)",
                    bucketName, DEFAULT_SCOPE, resourceType
            );

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



    @Override
    public boolean upsert(String resourceType , VersionedResource versionedResource, String bucketName) {

        try{
            String connectionName =  getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            Bucket bucket = cluster.bucket(bucketName);
            Scope scope = bucket.scope("Resources");
            Collection collection = scope.collection(resourceType);

            TransactionResult result = cluster.transactions().run(ctx -> {
                // Insert
                ctx.insert(collection,versionedResource.historyKey, versionedResource.historyCopy);

                //replace
                TransactionGetResult doc = ctx.get(collection, versionedResource.newKey);
                ctx.replace( doc , versionedResource.newResource);


            });
            return true;


         /*   String documentKey = resourceType + "/" + resource.getIdElement().getIdPart();
            System.out.println("document key --- ");
            System.out.println(documentKey);
            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);

            String insertQuery = String.format(
                    "INSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ($key, $value)",
                    bucketName, DEFAULT_SCOPE, resourceType
            );

            JsonObject params = JsonObject.create()
                    .put("key", documentKey)
                    .put("value", JsonObject.fromJson(resourceJson));

            QueryResult result = cluster.query(insertQuery, queryOptions().parameters(params));
            logger.info("Successfully created {} with ID: {}", resourceType, documentKey);
            return Optional.of(resource);*/

        } catch (TransactionFailedException e) {
            throw e;
        } catch (Exception e){
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

            logger.info("Query execution time : " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");

            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            rows.stream()  // Use sequential stream to preserve FTS sort order
                    .map(row -> (T) parser.parseResource(row.toString()))
                    .forEach(resources::add);
            logger.info("Execution time after HAPI parsing result: " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");

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
