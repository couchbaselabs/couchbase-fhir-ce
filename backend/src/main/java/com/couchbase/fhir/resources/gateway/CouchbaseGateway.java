package com.couchbase.fhir.resources.gateway;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.result.SearchResult;
import com.couchbase.client.core.error.*;
import com.couchbase.fhir.resources.exceptions.DatabaseUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Central gateway for all Couchbase operations with circuit breaker pattern.
 * 
 * This is the SINGLE place where we check database connectivity.
 * All services (SearchService, PostService, PutService, etc.) call through this gateway.
 * 
 * Benefits:
 * - Clean fail-fast when DB is down (no scattered try/catch blocks)
 * - Single exception type (DatabaseUnavailableException -> 503)
 * - Circuit breaker prevents overwhelming a recovering database
 * - Health endpoint reflects actual DB state for HAProxy routing
 */
@Component
public class CouchbaseGateway {
    
    private static final Logger logger = LoggerFactory.getLogger(CouchbaseGateway.class);
    
    // Circuit breaker state
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private static final long CIRCUIT_RESET_TIMEOUT_MS = 30_000; // 30 seconds
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Execute an operation with the Couchbase cluster.
     * Fails fast with DatabaseUnavailableException if connection is down or circuit is open.
     * 
     * @param operation The operation to execute with the cluster
     * @return Result of the operation
     * @throws DatabaseUnavailableException if database is unavailable
     */
    public <T> T withCluster(String connectionName, Function<Cluster, T> operation) {
        // Check circuit breaker first (fast path)
        if (circuitOpen.get()) {
            long timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get();
            if (timeSinceFailure < CIRCUIT_RESET_TIMEOUT_MS) {
                logger.debug("⚡ Circuit breaker OPEN - rejecting operation");
                throw new DatabaseUnavailableException("Database circuit breaker is open");
            } else {
                // Try to close the circuit (half-open state)
                logger.info("⚡ Circuit breaker timeout elapsed - attempting to close circuit");
                circuitOpen.set(false);
            }
        }
        
        // Check if connection exists
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            openCircuit();
            throw new DatabaseUnavailableException("No active Couchbase connection: " + connectionName);
        }
        
        try {
            // Execute the operation
            T result = operation.apply(cluster);
            
            // Success - ensure circuit is closed
            if (circuitOpen.get()) {
                logger.info("✅ Circuit breaker CLOSED - database recovered");
                circuitOpen.set(false);
            }
            
            return result;
            
        } catch (Exception e) {
            // Check if this is a connectivity error
            if (isConnectivityError(e)) {
                openCircuit();
                throw new DatabaseUnavailableException("Couchbase operation failed: " + e.getMessage(), e);
            }
            // Re-throw application errors as-is
            throw e;
        }
    }
    
    /**
     * Execute a query operation.
     */
    public QueryResult query(String connectionName, String query) {
        return withCluster(connectionName, cluster -> cluster.query(query));
    }
    
    /**
     * Execute a query operation with options.
     */
    public QueryResult query(String connectionName, String query, com.couchbase.client.java.query.QueryOptions options) {
        return withCluster(connectionName, cluster -> cluster.query(query, options));
    }
    
    /**
     * Execute an FTS search operation.
     */
    public SearchResult searchQuery(String connectionName, String indexName, 
                                   SearchQuery searchQuery, SearchOptions options) {
        return withCluster(connectionName, 
            cluster -> cluster.searchQuery(indexName, searchQuery, options));
    }
    
    /**
     * Get a collection for KV operations.
     */
    public Collection getCollection(String connectionName, String bucketName, 
                                   String scopeName, String collectionName) {
        return connectionService.getCollection(connectionName, bucketName, scopeName, collectionName);
    }
    
    /**
     * Get the Cluster for transaction operations.
     * Used by services that need to run transactions (PUT, DELETE, Bundle processing).
     * Still goes through gateway validation to ensure circuit breaker is enforced.
     */
    public Cluster getClusterForTransaction(String connectionName) {
        // Check circuit breaker first
        if (circuitOpen.get()) {
            long timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get();
            if (timeSinceFailure < CIRCUIT_RESET_TIMEOUT_MS) {
                logger.debug("⚡ Circuit breaker OPEN - rejecting transaction request");
                throw new DatabaseUnavailableException("Database circuit breaker is open");
            }
        }
        
        // Check if connection exists
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            openCircuit();
            throw new DatabaseUnavailableException("No active Couchbase connection: " + connectionName);
        }
        
        return cluster;
    }
    
    /**
     * Check if database connection exists (regardless of circuit state).
     * Used by health checks to determine if we should attempt recovery.
     */
    public boolean hasConnection(String connectionName) {
        return connectionService.hasActiveConnection(connectionName);
    }
    
    /**
     * Check if database is available (for health checks).
     * Returns false if circuit is open OR connection doesn't exist.
     */
    public boolean isAvailable(String connectionName) {
        if (circuitOpen.get()) {
            return false;
        }
        return connectionService.hasActiveConnection(connectionName);
    }
    
    /**
     * Get circuit breaker status for monitoring.
     */
    public boolean isCircuitOpen() {
        return circuitOpen.get();
    }
    
    /**
     * Open the circuit breaker due to connectivity failure.
     */
    private void openCircuit() {
        if (!circuitOpen.getAndSet(true)) {
            logger.error("⚡ Circuit breaker OPENED - database unavailable");
        }
        lastFailureTime.set(System.currentTimeMillis());
    }
    
    /**
     * Determine if an exception indicates a connectivity/availability issue.
     * Uses specific Couchbase SDK exception types for accurate detection.
     */
    private boolean isConnectivityError(Throwable t) {
        while (t != null) {
            // Check for specific Couchbase SDK exceptions
            if (t instanceof TimeoutException ||
                t instanceof AmbiguousTimeoutException ||
                t instanceof UnambiguousTimeoutException ||
                t instanceof RequestCanceledException ||
                t instanceof ServiceNotAvailableException ||
                t instanceof TemporaryFailureException ||
                t instanceof ConnectException ||
                t instanceof IOException) {
                return true;
            }
            
            // Also check message patterns for cases where exceptions are wrapped
            String message = t.getMessage();
            if (message != null && (
                message.contains("No active connection") ||
                message.contains("Connection refused") ||
                message.contains("Could not connect"))) {
                return true;
            }
            
            t = t.getCause();
        }
        return false;
    }
}

