package com.couchbase.admin.sampledata.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.sampledata.model.SampleDataRequest;
import com.couchbase.admin.sampledata.model.SampleDataResponse;
import com.couchbase.admin.sampledata.model.SampleDataProgress;
import com.couchbase.admin.users.service.UserService;
import com.couchbase.admin.users.model.User;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.service.FhirResourceStorageHelper;
import com.couchbase.client.java.Cluster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service for loading sample FHIR data from embedded resources
 */
@Service
public class SampleDataService {
    
    private static final Logger log = LoggerFactory.getLogger(SampleDataService.class);
    
    // Sample data file paths
    private static final String SYNTHEA_SAMPLE_DATA_PATH = "static/sample-data/synthea-patients-sample.zip";
    private static final String USCORE_SAMPLE_DATA_PATH = "static/sample-data/us-core-examples.zip";
    private static final String ONC_SAMPLE_DATA_PATH = "static/sample-data/onc-examples.zip";
    
    // Debug mode - set to true to process only 1 file for debugging
    private static final boolean DEBUG_MODE_SINGLE_FILE = false;
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirBundleProcessingService bundleProcessor;
    
    @Autowired
    private FhirResourceStorageHelper storageHelper;
    
    @Autowired
    private UserService userService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Inner class to hold file data for concurrent processing
    private static class FileData {
        final String fileName;
        final byte[] content;
        
        FileData(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }
    }
    
    /**
     * Get the sample data file path based on the sample type
     */
    private String getSampleDataPath(String sampleType) {
        if (sampleType == null) {
            return SYNTHEA_SAMPLE_DATA_PATH; // Default fallback
        }
        
        switch (sampleType.toLowerCase()) {
            case "us-core":
                return USCORE_SAMPLE_DATA_PATH;
            case "onc":
                return ONC_SAMPLE_DATA_PATH;
            case "synthea":
                return SYNTHEA_SAMPLE_DATA_PATH;
            default:
                return SYNTHEA_SAMPLE_DATA_PATH; // Default fallback
        }
    }
    
    /**
     * Check if a ZIP entry should be processed (filters out macOS metadata files)
     */
    private boolean shouldProcessEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        
        String name = entry.getName();
        
        // Must be a JSON file
        if (!name.endsWith(".json")) {
            return false;
        }
        
        // Filter out macOS metadata files
        if (name.contains("__MACOSX") || 
            name.contains(".DS_Store") || 
            name.startsWith("._") ||
            name.contains("/._")) { // Also check for nested ._* files
            return false;
        }
        
        return true;
    }
    
    /**
     * Load sample FHIR data into the specified bucket
     */
    public SampleDataResponse loadSampleData(SampleDataRequest request) {
        log.info("Loading sample data for connection: {}, bucket: {}", 
                request.getConnectionName(), request.getBucketName());
        
        try {
            // Get connection
            Cluster cluster = connectionService.getConnection(request.getConnectionName());
            if (cluster == null) {
                return new SampleDataResponse(false, "Connection not found: " + request.getConnectionName());
            }
            
            // Load and process sample data
            int resourcesLoaded = 0;
            int patientsLoaded = 0;
            
            // First pass: count total files for progress tracking
            int totalFiles = 0;
            String sampleDataPath = getSampleDataPath(request.getSampleType());
            try (InputStream zipStream = SampleDataService.class.getClassLoader().getResourceAsStream(sampleDataPath);
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        totalFiles++;
                        if (DEBUG_MODE_SINGLE_FILE) {
                            log.warn("🐛 DEBUG MODE: Processing only 1 file for debugging");
                            break; // Only count 1 file in debug mode
                        }
                    }
                }
            }
            
            // Second pass: process files with progress tracking
            int processedFiles = 0;
            try (InputStream zipStream = SampleDataService.class.getClassLoader().getResourceAsStream(sampleDataPath);
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        String fileName = entry.getName();
                        log.info("Processing file {}/{}: {}", processedFiles + 1, totalFiles, fileName);
                        
                        // Read the JSON content using ByteArrayOutputStream for dynamic sizing
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192]; // Use a reasonable buffer size
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] jsonBytes = baos.toByteArray();
                        
                        // Process the data - check if it's a Bundle or individual resource
                        String jsonContent = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                        
                        log.debug("🔍 Processing JSON content (length: {} chars)", jsonContent.length());
                        
                        Map<String, Integer> resourceCounts = processJsonContent(jsonContent, request.getConnectionName(), request.getBucketName(), request.getSampleType());
                        resourcesLoaded += resourceCounts.getOrDefault("resources", 0);
                        patientsLoaded += resourceCounts.getOrDefault("patients", 0);
                        
                        processedFiles++;
                        double progress = (double) processedFiles / totalFiles * 100;
                        log.info("Progress: {:.1f}% ({} resources, {} patients loaded so far)", 
                                progress, resourcesLoaded, patientsLoaded);
                        
                        if (DEBUG_MODE_SINGLE_FILE) {
                            log.warn("🐛 DEBUG MODE: Stopping after processing 1 file. Total processed: {} resources, {} patients", 
                                resourcesLoaded, patientsLoaded);
                            break; // Only process 1 file in debug mode
                        }
                    }
                }
            }
            
            SampleDataResponse response = new SampleDataResponse(
                true, 
                "Sample data loaded successfully", 
                resourcesLoaded, 
                patientsLoaded
            );
            response.setBucketName(request.getBucketName());
            response.setConnectionName(request.getConnectionName());
            
            log.info("Successfully loaded {} resources ({} patients) into bucket: {}", 
                    resourcesLoaded, patientsLoaded, request.getBucketName());
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to load sample data: {}", e.getMessage(), e);
            return new SampleDataResponse(false, "Failed to load sample data: " + e.getMessage());
        }
    }
    
    /**
     * Check if sample data is available
     */
    public SampleDataResponse checkSampleDataAvailability() {
        try {
            ClassPathResource syntheaResource = new ClassPathResource(SYNTHEA_SAMPLE_DATA_PATH);
            ClassPathResource uscoreResource = new ClassPathResource(USCORE_SAMPLE_DATA_PATH);
            
            boolean syntheaAvailable = syntheaResource.exists();
            boolean uscoreAvailable = uscoreResource.exists();
            
            if (syntheaAvailable || uscoreAvailable) {
                String message = "Sample data available: ";
                if (syntheaAvailable) message += "Synthea ";
                if (uscoreAvailable) message += "US Core ";
                return new SampleDataResponse(true, message.trim());
            } else {
                return new SampleDataResponse(false, "No sample data files found");
            }
        } catch (Exception e) {
            log.error("Error checking sample data availability: {}", e.getMessage());
            return new SampleDataResponse(false, "Error checking sample data: " + e.getMessage());
        }
    }
    
    /**
     * Process a FHIR bundle using our sophisticated Bundle processor with lenient validation for sample data
     */
    public Map<String, Integer> processBundle(String bundleJson, String connectionName, String bucketName) {
        Map<String, Integer> counts = new HashMap<>();
        int resourceCount = 0;
        int patientCount = 0;
        
        try {
            log.debug("🚀 Starting bundle processing for connection: {}, bucket: {}", connectionName, bucketName);
            log.debug("📦 Bundle JSON length: {} chars", bundleJson.length());
            
            // Use our sophisticated Bundle processor with NO VALIDATION for sample data
            // Sample data is vetted and bundled, so validation is unnecessary and slow
            // The processor handles:
            // - UUID reference resolution
            // - Sequential processing  
            // - Proper document keys (resourceType/id)
            // - Audit trails
            // - SKIPS validation for maximum performance
            // Create a config object that skips validation for sample data performance
            com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig sampleDataConfig = 
                new com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig();
            sampleDataConfig.setValidationMode("disabled"); // Skip validation for sample data
            
            Bundle responseBundle = bundleProcessor.processBundleTransaction(bundleJson, connectionName, bucketName, sampleDataConfig);
            
            // Count successful entries from the response
            if (responseBundle != null && responseBundle.getEntry() != null) {
                for (Bundle.BundleEntryComponent entry : responseBundle.getEntry()) {
                    if (entry.getResponse() != null && entry.getResponse().getStatus() != null) {
                        String status = entry.getResponse().getStatus();
                        if (status.startsWith("201") || status.startsWith("200")) { // Created or OK
                            resourceCount++;
                            
                            // Count patients specifically
                            if (entry.getResource() != null && "Patient".equals(entry.getResource().getResourceType().name())) {
                                patientCount++;
                            }
                        }
                    }
                }
            }
            
            log.debug("Processed bundle with {} resources ({} patients) using Bundle processor with lenient validation", 
                    resourceCount, patientCount);
            
        } catch (Exception e) {
            log.error("Error processing FHIR bundle with Bundle processor: {}", e.getMessage());
            // Continue processing other bundles even if one fails
        }
        
        counts.put("resources", resourceCount);
        counts.put("patients", patientCount);
        return counts;
    }
    
    /**
     * Process JSON content - determines if it's a Bundle or individual resource and processes accordingly
     */
    private Map<String, Integer> processJsonContent(String jsonContent, String connectionName, String bucketName, String sampleType) {
        Map<String, Integer> counts = new HashMap<>();
        
        try {
            // Parse JSON to determine resource type
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(jsonContent);
            String resourceType = jsonNode.get("resourceType").asText();
            
            log.debug("📋 Detected resource type: {}", resourceType);
            
            if ("Bundle".equals(resourceType)) {
                // Process as Bundle (Synthea data)
                log.debug("🔄 Processing as Bundle");
                return processBundle(jsonContent, connectionName, bucketName);
            } else {
                // Process as individual resource (US Core data)  
                log.debug("🔄 Processing as individual resource");
                return processIndividualResource(jsonContent, connectionName, bucketName);
            }
        } catch (Exception e) {
            log.error("❌ Error processing JSON content: {} (Exception: {})", e.getMessage(), e.getClass().getSimpleName(), e);
            log.error("   JSON content length: {} chars", jsonContent.length());
            counts.put("resources", 0);
            counts.put("patients", 0);
            return counts;
        }
    }
    
    /**
     * Process an individual FHIR resource (for US Core data)
     */
    private Map<String, Integer> processIndividualResource(String resourceJson, String connectionName, String bucketName) {
        Map<String, Integer> counts = new HashMap<>();
        int resourceCount = 0;
        int patientCount = 0;
        
        try {
            // Get connection
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                log.error("Connection not found: {}", connectionName);
                counts.put("resources", 0);
                counts.put("patients", 0);
                return counts;
            }

            // Use the storage helper to process and store with NO VALIDATION for sample data performance
            // Sample data is vetted and bundled, so validation is unnecessary and slow
            Map<String, Object> result = storageHelper.processAndStoreResource(resourceJson, cluster, bucketName, "SAMPLE_DATA_LOAD", false, true);

            if ((Boolean) result.get("success")) {
                resourceCount = 1;
                String resourceType = (String) result.get("resourceType");
                String resourceId = (String) result.get("resourceId");

                // Count patients
                if ("Patient".equals(resourceType)) {
                    patientCount = 1;
                }

                log.debug("Successfully processed {} resource with ID: {} using storage helper", resourceType, resourceId);
            } else {
                log.error("Failed to process resource: {}", result.get("error"));
            }

        } catch (Exception e) {
            log.error("Error processing individual resource: {}", e.getMessage());
        }
        
        counts.put("resources", resourceCount);
        counts.put("patients", patientCount);
        return counts;
    }
    
    /**
     * Get sample data statistics (without loading)
     */
    public SampleDataResponse getSampleDataStats() {
        try {
            int totalResources = 0;
            int totalPatients = 0;
            
            // Default to Synthea for stats - could be enhanced to take sampleType parameter
            String sampleDataPath = getSampleDataPath("synthea");
            try (InputStream zipStream = SampleDataService.class.getClassLoader().getResourceAsStream(sampleDataPath);
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        // Read the JSON content using ByteArrayOutputStream for dynamic sizing
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] jsonBytes = baos.toByteArray();
                        
                        // Parse the bundle to count resources
                        JsonNode bundle = objectMapper.readTree(new ByteArrayInputStream(jsonBytes));
                        if (bundle.has("entry")) {
                            JsonNode entries = bundle.get("entry");
                            if (entries != null && entries.isArray()) {
                                for (JsonNode entryNode : entries) {
                                    JsonNode resource = entryNode.get("resource");
                                    if (resource != null) {
                                        totalResources++;
                                        String resourceType = resource.get("resourceType").asText();
                                        if ("Patient".equals(resourceType)) {
                                            totalPatients++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            SampleDataResponse response = new SampleDataResponse(
                true, 
                "Sample data contains " + totalPatients + " patients with " + totalResources + " total resources"
            );
            response.setResourcesLoaded(totalResources);
            response.setPatientsLoaded(totalPatients);
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to get sample data stats: {}", e.getMessage());
            return new SampleDataResponse(false, "Failed to analyze sample data: " + e.getMessage());
        }
    }
    
    /**
     * Load sample data with progress tracking capability
     * This method can be extended to support real-time progress updates via WebSocket or Server-Sent Events
     */
    public SampleDataResponse loadSampleDataWithProgress(SampleDataRequest request, ProgressCallback callback) {
        log.info("Loading sample data with progress tracking for connection: {}, bucket: {}", 
                request.getConnectionName(), request.getBucketName());
        
        try {
            // Get connection
            Cluster cluster = connectionService.getConnection(request.getConnectionName());
            if (cluster == null) {
                return new SampleDataResponse(false, "Connection not found: " + request.getConnectionName());
            }
            
            // Load and process sample data with progress callbacks
            int resourcesLoaded = 0;
            int patientsLoaded = 0;
            
            // First pass: count total files
            int fileCount = 0;
            String sampleDataPath = getSampleDataPath(request.getSampleType());
            try (InputStream zipStream = SampleDataService.class.getClassLoader().getResourceAsStream(sampleDataPath);
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        fileCount++;
                    }
                }
            }
            final int totalFiles = fileCount;
            
            // Notify start
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress(totalFiles, 0, "Starting...");
                progress.setStatus("INITIATED");
                progress.setMessage("Initializing sample data loading...");
                callback.onProgress(progress);
            }
            
            // Second pass: collect all files for concurrent processing
            List<FileData> filesToProcess = new ArrayList<>();
            try (InputStream zipStream = SampleDataService.class.getClassLoader().getResourceAsStream(sampleDataPath);
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        String fileName = entry.getName();
                        
                        // Read the JSON content using ByteArrayOutputStream for dynamic sizing
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] jsonBytes = baos.toByteArray();
                        
                        filesToProcess.add(new FileData(fileName, jsonBytes));
                    }
                }
            }
            
            // Process files in PARALLEL with limited concurrency
            // With 120s transaction timeout, parallel processing is now safe
            // Limit to 4 threads to balance speed vs. transaction contention
            AtomicInteger processedFiles = new AtomicInteger(0);
            AtomicInteger totalResourcesLoaded = new AtomicInteger(0);
            AtomicInteger totalPatientsLoaded = new AtomicInteger(0);
            
            // Create thread pool for concurrent processing (4 threads for good balance)
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, filesToProcess.size()));
            
            try {
                // Process files concurrently
                List<CompletableFuture<Void>> futures = filesToProcess.stream()
                    .map(fileData -> CompletableFuture.runAsync(() -> {
                        try {
                            String fileName = fileData.fileName;
                            String jsonContent = new String(fileData.content, java.nio.charset.StandardCharsets.UTF_8);
                            
                            // Update progress - BEFORE processing file
                            if (callback != null) {
                                int currentProcessed = processedFiles.get();
                                SampleDataProgress progress = new SampleDataProgress(totalFiles, currentProcessed, fileName);
                                progress.setStatus("IN_PROGRESS");
                                progress.setMessage("Processing " + fileName + "...");
                                progress.setResourcesLoaded(totalResourcesLoaded.get());
                                progress.setPatientsLoaded(totalPatientsLoaded.get());
                                callback.onProgress(progress);
                            }
                            
                            // Process the data
                            Map<String, Integer> resourceCounts = processJsonContent(jsonContent, request.getConnectionName(), request.getBucketName(), request.getSampleType());
                            int resources = resourceCounts.getOrDefault("resources", 0);
                            int patients = resourceCounts.getOrDefault("patients", 0);
                            
                            // Update counters atomically
                            totalResourcesLoaded.addAndGet(resources);
                            totalPatientsLoaded.addAndGet(patients);
                            int completed = processedFiles.incrementAndGet();
                            
                            // Update progress - AFTER processing file
                            if (callback != null) {
                                SampleDataProgress progress = new SampleDataProgress(totalFiles, completed, fileName);
                                progress.setStatus("IN_PROGRESS");
                                progress.setMessage("Completed " + fileName + " - " + totalResourcesLoaded.get() + " resources loaded");
                                progress.setResourcesLoaded(totalResourcesLoaded.get());
                                progress.setPatientsLoaded(totalPatientsLoaded.get());
                                callback.onProgress(progress);
                            }
                            
                            log.info("Processed file {}/{}: {} - {} resources, {} patients loaded so far", 
                                    completed, totalFiles, fileName, totalResourcesLoaded.get(), totalPatientsLoaded.get());
                        } catch (Exception e) {
                            log.error("Error processing file {}: {}", fileData.fileName, e.getMessage());
                        }
                    }, executor))
                    .collect(java.util.stream.Collectors.toList());
                
                // Wait for all files to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                // Update final values
                resourcesLoaded = totalResourcesLoaded.get();
                patientsLoaded = totalPatientsLoaded.get();
                
            } finally {
                executor.shutdown();
            }
            
            // Final progress update
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress(totalFiles, processedFiles.get(), "All files completed");
                progress.setStatus("COMPLETED");
                progress.setMessage("Sample data loading completed successfully - " + resourcesLoaded + " resources (" + patientsLoaded + " patients) loaded");
                progress.setResourcesLoaded(resourcesLoaded);
                progress.setPatientsLoaded(patientsLoaded);
                callback.onProgress(progress);
            }
            
            // Create test users if US-Core sample was loaded
            if ("uscore".equalsIgnoreCase(request.getSampleType()) || "us-core".equalsIgnoreCase(request.getSampleType())) {
                log.info("Creating test users for US-Core sample data");
                createTestUsersForUSCore(callback);
            }
            
            SampleDataResponse response = new SampleDataResponse(
                true, 
                "Sample data loaded successfully", 
                resourcesLoaded, 
                patientsLoaded
            );
            response.setBucketName(request.getBucketName());
            response.setConnectionName(request.getConnectionName());
            
            log.info("Successfully loaded {} resources ({} patients) into bucket: {}", 
                    resourcesLoaded, patientsLoaded, request.getBucketName());
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to load sample data: {}", e.getMessage(), e);
            
            // Notify error
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress();
                progress.setStatus("ERROR");
                progress.setMessage("Failed to load sample data: " + e.getMessage());
                callback.onProgress(progress);
            }
            
            return new SampleDataResponse(false, "Failed to load sample data: " + e.getMessage());
        }
    }
    
    /**
     * Create test users for US-Core sample data
     * Creates a Patient user (Amy Shaw) and a Practitioner user (Ronald Bone)
     * Both users are for testing only and cannot login to the UI
     */
    private void createTestUsersForUSCore(ProgressCallback callback) {
        try {
            log.info("Creating test users for US-Core sample data...");
            
            // Notify progress
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress();
                progress.setStatus("IN_PROGRESS");
                progress.setMessage("Creating test users (Patient & Practitioner)...");
                callback.onProgress(progress);
            }
            
            // Create Patient user: Amy Shaw
            User patientUser = new User();
            patientUser.setId("amy.shaw@example.com");
            patientUser.setEmail("amy.shaw@example.com");
            patientUser.setUsername("Amy Shaw");
            patientUser.setRole("patient");
            patientUser.setAuthMethod("local");
            patientUser.setStatus("active");
            patientUser.setPasswordHash("password123"); // Will be hashed by UserService
            patientUser.setFhirUser("Patient/example");
            
            // Create Practitioner user: Ronald Bone
            User practitionerUser = new User();
            practitionerUser.setId("ronald.bone@example.org");
            practitionerUser.setEmail("ronald.bone@example.org");
            practitionerUser.setUsername("Ronald Bone");
            practitionerUser.setRole("practitioner");
            practitionerUser.setAuthMethod("local");
            practitionerUser.setStatus("active");
            practitionerUser.setPasswordHash("password123"); // Will be hashed by UserService
            practitionerUser.setFhirUser("Practitioner/practitioner-1");
            
            // Try to create users - if they already exist, log and continue
            try {
                userService.createUser(patientUser, "system");
                log.info("✅ Created test patient user: amy.shaw@example.com");
            } catch (IllegalArgumentException e) {
                if (e.getMessage().contains("already exists")) {
                    log.info("ℹ️  Patient user already exists, skipping: amy.shaw@example.com");
                } else {
                    throw e;
                }
            }
            
            try {
                userService.createUser(practitionerUser, "system");
                log.info("✅ Created test practitioner user: ronald.bone@example.org");
            } catch (IllegalArgumentException e) {
                if (e.getMessage().contains("already exists")) {
                    log.info("ℹ️  Practitioner user already exists, skipping: ronald.bone@example.org");
                } else {
                    throw e;
                }
            }
            
            // Notify completion
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress();
                progress.setStatus("COMPLETED");
                progress.setMessage("Test users created successfully");
                callback.onProgress(progress);
            }
            
            log.info("✅ Test users created for US-Core sample data");
            
        } catch (Exception e) {
            log.error("⚠️  Failed to create test users (non-fatal): {}", e.getMessage(), e);
            // Don't fail the entire sample load if user creation fails
        }
    }
    
    /**
     * Callback interface for progress updates
     */
    public interface ProgressCallback {
        void onProgress(SampleDataProgress progress);
    }
} 