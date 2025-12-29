package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.users.bulkGroup.service.GroupAdminService;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.common.config.FhirServerConfig;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.service.FtsSearchService;
import com.couchbase.fhir.resources.util.BulkJob;
import com.couchbase.fhir.resources.util.BulkOutput;
import com.couchbase.fhir.resources.util.BulkTask;
import com.couchbase.fhir.resources.util.NDJsonWrite;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helger.commons.annotation.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class BulkImportProvider {

    private static final Logger logger = LoggerFactory.getLogger(BulkImportProvider.class);

    private static final String BULK_EXPORT_DIR = "data/exports";

    @Autowired
    private FhirBucketConfigService bucketConfigService;


    private final ConnectionService connectionService;

    private final FhirContext fhirContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final FhirBundleProcessingService fhirBundleProcessingService;

    private final GroupAdminService groupAdminService;

    private final FhirServerConfig fhirServerConfig;

    private FtsSearchService ftsSearchService;

    public BulkImportProvider(FhirContext fhirContext , ConnectionService connectionService , FhirBundleProcessingService fhirBundleProcessingService , GroupAdminService groupAdminService , FhirServerConfig fhirServerConfig , FtsSearchService ftsSearchService) {
        this.fhirContext = fhirContext;
        this.connectionService = connectionService;
        this.fhirBundleProcessingService = fhirBundleProcessingService;
        this.groupAdminService = groupAdminService;
        this.fhirServerConfig = fhirServerConfig;
        this.ftsSearchService = ftsSearchService;
    }


    @Operation(name = "$import", idempotent = false, manualResponse = true)
    public void importBulkData(@ResourceParam Parameters parameters , RequestDetails requestDetails , HttpServletRequest request, HttpServletResponse response) throws IOException {

        String bucketName = TenantContextHolder.getTenantId();
        logger.info("📦 Starting bulk import for : {}", bucketName);


        BulkJob bulkJob = new BulkJob();
        bulkJob.setJobId(UUID.randomUUID().toString());
        bulkJob.setStatus("PENDING");
        List<BulkTask> taskList = new ArrayList<>();

        parameters.getParameter().stream()
                .filter(p -> "input".equals(p.getName()))
                .forEach(input -> {
                    BulkTask bulkTask = new BulkTask();
                    String url = getPartValue(input, "url");
                    bulkTask.setTaskId(UUID.randomUUID().toString());
                    bulkTask.setFileUrl(url);
                    taskList.add(bulkTask);
                });

        bulkJob.setTaskList(taskList);
        String documentKey = "BulkJob" + "/" + bulkJob.getJobId();

        com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");

        com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                .scope("Resources")
                .collection("BulkJob");

        collection.insert(documentKey, bulkJob);

        Parameters result = new Parameters();
        result.addParameter().setName("jobId").setValue(new StringType(bulkJob.getJobId()));

        executor.submit(() -> processJob(bulkJob , bucketName));

        // 📨 Send HTTP headers
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader("Content-Location", "JOB URL");
        response.setContentType("application/fhir+json");

        // 🧾 Write JSON body
        requestDetails.getFhirContext()
                .newJsonParser()
                .setPrettyPrint(true)
                .encodeResourceToWriter(result, response.getWriter());

        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader("Content-Location", "JOB URL");

    }

    public void processJob(BulkJob bulkJob , String bucketName){

        try{
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");

            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                    .scope("Resources")
                    .collection("BulkJob");

            String documentKey = "BulkJob" + "/" + bulkJob.getJobId();
            bulkJob.setStatus("INPROGRESS");
            collection.upsert(documentKey, bulkJob);

            for (BulkTask task : bulkJob.getTaskList()) {

                String fileUrl= task.getFileUrl();

                int processedFiles = 0;
                URL url = new URL(fileUrl);
                try (InputStream zipStream = url.openStream();
                     ZipInputStream zis = new ZipInputStream(zipStream)) {

                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (shouldProcessEntry(entry)) {
                            String fileName = entry.getName();
                            logger.info("Processing file {}", fileName);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = zis.read(buffer)) != -1) {
                                baos.write(buffer, 0, bytesRead);
                            }
                            byte[] jsonBytes = baos.toByteArray();
                            String jsonContent = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);

                            logger.debug("🔍 Processing JSON content (length: {} chars)", jsonContent.length());
                            com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bulkImportConfig =
                                    new com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig();
                            bulkImportConfig.setValidationMode("disabled");
                            fhirBundleProcessingService.processBundleTransaction(jsonContent , "default" , bucketName , bulkImportConfig);

                            processedFiles++;

                        }
                    }
                    bulkJob.setStatus("COMPLETED");
                    collection.upsert(documentKey, bulkJob);
                    logger.info("Total {} files processed successfully ", processedFiles);
                }

            }


        }catch(Exception e){
            logger.error("Error in bulk job process "+e.getMessage());
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

    @Operation(name = "$bulkstatus", idempotent = true, manualResponse = true)
    public void getBulkJobStatus(
            @OperationParam(name = "jobId") String jobId,
            HttpServletResponse response
    ) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        String documentKey = "BulkJob" + "/" + jobId;

        com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");

        com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                .scope("Resources")
                .collection("BulkJob");

        com.couchbase.client.java.kv.GetResult result = collection.get(documentKey);

        // Set HTTP response
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(result.contentAsObject().toString());
    }


    private String getParameterValue(Parameters parameters, String name) {
        return parameters.getParameter()
                .stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .map(p -> {
                    IBaseDatatype value = p.getValue();
                    if (value instanceof StringType) return ((StringType) value).getValue();
                    if (value instanceof UriType) return ((UriType) value).getValue();
                    return null;
                })
                .orElse(null);
    }


    private String getPartValue(Parameters.ParametersParameterComponent param, String partName) {
        return param.getPart()
                .stream()
                .filter(p -> partName.equals(p.getName()))
                .findFirst()
                .map(p -> {
                    IBaseDatatype value = p.getValue();
                    if (value instanceof StringType) return ((StringType) value).getValue();
                    if (value instanceof UriType) return ((UriType) value).getValue();
                    return null;
                })
                .orElse(null);
    }



    @Operation(name = "$export", idempotent = false, manualResponse = true)
    public void exportBulkData(@ResourceParam Parameters parameters , RequestDetails requestDetails , HttpServletRequest request, HttpServletResponse response) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        Map<String, String[]> params = requestDetails.getParameters();
        String bulkGroupId = params.get("bulkgroupid")[0];

        logger.info("📦 Starting bulk export for : {} , {}", bucketName , bulkGroupId);

        Group bulkGroup = groupAdminService.getGroupById(bulkGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + bulkGroupId));


        List<String> patientIds = bulkGroup.getMember().stream()
                .map(Group.GroupMemberComponent::getEntity)
                .filter(Objects::nonNull)
                .map(Reference::getReference)
                .filter(ref -> ref.startsWith("Patient/"))
                .toList();

        if (patientIds.isEmpty()) {
            response.sendError(400, "Group has no Patient members");
        }

        BulkJob bulkJob = new BulkJob();
        bulkJob.setJobId(UUID.randomUUID().toString());
        bulkJob.setStatus("PENDING");
        List<BulkOutput> outputs = new ArrayList<>();

        Path baseDir = Paths.get(BULK_EXPORT_DIR);
        Files.createDirectories(baseDir);

        Path jobDir = baseDir.resolve(bulkJob.getJobId());
        Files.createDirectories(jobDir);

        com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");


        //create patient file and write to it
        Path patientFile = jobDir.resolve("Patient.ndjson");

        try (NDJsonWrite writer = new NDJsonWrite(patientFile)) {

            com.couchbase.client.java.Collection patinetCollection = cluster.bucket(bucketName)
                    .scope("Resources")
                    .collection("Patient");

            for (String patientId : patientIds) {

                try {
                    GetResult result = patinetCollection.get(patientId);
                    String json = result.contentAsObject().toString();
                    writer.writeLine(json);

                } catch (DocumentNotFoundException e) {
                    logger.warn("Patient not found: {}", patientId);
                }
            }

            BulkOutput bulkOutput = new BulkOutput();
            bulkOutput.setType("Patient");
            String patientUrl =  fhirServerConfig.getNormalizedBaseUrl()+"/" + BULK_EXPORT_DIR+"/" + bulkJob.getJobId() + "/Patient.ndjson";
            bulkOutput.setUrl(patientUrl);
            outputs.add(bulkOutput);
        }



        BulkOutput obsOutput = exportToFile(bulkJob.getJobId() , bucketName , jobDir , patientIds , cluster , "Observation");
        outputs.add(obsOutput);

        BulkOutput conditionOutput = exportToFile(bulkJob.getJobId() , bucketName , jobDir , patientIds , cluster , "Condition");
        outputs.add(conditionOutput);
        BulkOutput encounterOutput = exportToFile(bulkJob.getJobId() , bucketName , jobDir , patientIds , cluster , "Encounter");
        outputs.add(encounterOutput);

        BulkOutput procedureOutput = exportToFile(bulkJob.getJobId() , bucketName , jobDir , patientIds , cluster , "Procedure");
        outputs.add(procedureOutput);

        bulkJob.setOutput(outputs);
        bulkJob.setStatus("COMPLETED");

        //writing to DB
        com.couchbase.client.java.Collection bulkCollection = cluster.bucket(bucketName)
                .scope("Resources")
                .collection("BulkJob");
        String documentKey = "BulkJob" + "/" + bulkJob.getJobId();
        bulkCollection.insert(documentKey , bulkJob);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(bulkJob);
        response.getWriter().write(json);
    }


    public BulkOutput exportToFile(String jobId, String bucketName, Path jobDir , List<String> patientIds ,  com.couchbase.client.java.Cluster cluster , String resourceType) {
        SearchQuery query = buildExactQuery( patientIds);
        BulkOutput bulkOutput = new BulkOutput();
        FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForKeys(
                Collections.singletonList(query), resourceType, 0, 1, null);

        Path fileName = jobDir.resolve(resourceType+".ndjson");

        try (NDJsonWrite writer = new NDJsonWrite(fileName)) {

            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName)
                    .scope("Resources")
                    .collection(resourceType);

            for (String encounterId : ftsResult.getDocumentKeys()) {

                try {
                    GetResult result = collection.get(encounterId);
                    String json = result.contentAsObject().toString();
                    writer.writeLine(json);

                } catch (DocumentNotFoundException e) {
                    logger.warn("Resource not found: {}", encounterId);
                }
            }


            bulkOutput.setType(resourceType);
            String encounterUrl =  fhirServerConfig.getNormalizedBaseUrl()+"/" + BULK_EXPORT_DIR+"/" + jobId + "/"+resourceType+".ndjson";
            bulkOutput.setUrl(encounterUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bulkOutput;
    }

    @Operation(
            name = "$download",
            idempotent = true,
            manualResponse = true
    )
    public void downloadBulkFile(
            RequestDetails requestDetails,
            HttpServletResponse response
    ) throws IOException {

        Map<String, String[]> params = requestDetails.getParameters();

        String jobId = params.get("jobid")[0];
        String fileName = params.get("filename")[0];

        Path filePath = Paths.get(BULK_EXPORT_DIR, jobId, fileName);

        if (!Files.exists(filePath)) {
            response.sendError(404, "File not found");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/fhir+ndjson");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + fileName + "\""
        );

        try (InputStream in = Files.newInputStream(filePath);
             OutputStream out = response.getOutputStream()) {

            in.transferTo(out);
            out.flush();
        }
    }

    private SearchQuery buildExactQuery( List<String> values) {

        SearchQuery patientQuery =
                SearchQuery.disjuncts(
                        values.stream()
                                .map(ref ->
                                        SearchQuery.match(ref)
                                                .field("subject.reference")
                                )
                                .toArray(SearchQuery[]::new)
                );

        return patientQuery;
    }

}
