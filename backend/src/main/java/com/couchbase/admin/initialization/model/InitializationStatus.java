package com.couchbase.admin.initialization.model;

/**
 * Represents the initialization status of the FHIR system
 * In single-tenant mode, we expect exactly one bucket named "fhir"
 */
public class InitializationStatus {
    
    public enum Status {
        /**
         * No connection to Couchbase established yet
         */
        NOT_CONNECTED,
        
        /**
         * Connected to Couchbase but "fhir" bucket does not exist
         * User needs to manually create the bucket
         */
        BUCKET_MISSING,
        
        /**
         * "fhir" bucket exists but is not FHIR-initialized
         * Missing Admin scope, config collection, or fhir-config document
         * System needs to run initialization to create scopes/collections/indexes
         */
        BUCKET_NOT_INITIALIZED,
        
        /**
         * Fully initialized and ready
         * "fhir" bucket exists and has valid FHIR configuration
         */
        READY
    }
    
    private Status status;
    private String message;
    private String bucketName;
    private boolean hasConnection;
    private boolean bucketExists;
    private boolean isFhirInitialized;
    private boolean isAdminInitialized;
    
    public InitializationStatus() {
    }
    
    public InitializationStatus(Status status, String message) {
        this.status = status;
        this.message = message;
    }
    
    // Getters and setters
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getBucketName() {
        return bucketName;
    }
    
    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
    
    public boolean isHasConnection() {
        return hasConnection;
    }
    
    public void setHasConnection(boolean hasConnection) {
        this.hasConnection = hasConnection;
    }
    
    public boolean isBucketExists() {
        return bucketExists;
    }
    
    public void setBucketExists(boolean bucketExists) {
        this.bucketExists = bucketExists;
    }
    
    public boolean isFhirInitialized() {
        return isFhirInitialized;
    }
    
    public void setFhirInitialized(boolean fhirInitialized) {
        this.isFhirInitialized = fhirInitialized;
    }

    public boolean isAdminInitialized() {
        return isAdminInitialized;
    }

    public void setAdminInitialized(boolean adminInitialized) {
        this.isAdminInitialized = adminInitialized;
    }
}

