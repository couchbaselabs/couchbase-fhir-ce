package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Transaction;
import ca.uhn.fhir.rest.annotation.TransactionParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FHIRResourceService;
import com.couchbase.fhir.resources.service.FhirAuditService;
import com.couchbase.fhir.resources.service.UserAuditInfo;
import com.couchbase.fhir.resources.util.BundleProcessor;
import com.couchbase.fhir.validation.ValidationUtil;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * System-level provider for FHIR transaction operations.
 * This provider handles Bundle transactions that can contain multiple resource types.
 */
@Component
public class FhirTransactionProvider {

    @Autowired
    private FHIRResourceService serviceFactory;

    @Autowired
    private FhirContext fhirContext;

    @Transaction
    public Bundle transaction(@TransactionParam Bundle bundle) {
        String bucketName = TenantContextHolder.getTenantId();
        Bundle responseBundle = new Bundle();
        responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);

        BundleProcessor processor = new BundleProcessor();
        processor.process(bundle);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            MethodOutcome outcome = null;

            if (entry.getRequest().getMethod() == Bundle.HTTPVerb.POST) {
                outcome = createResource(resource, bucketName);
                System.out.println(resource.getClass().getSimpleName() + " id - " + outcome.getId().toUnqualifiedVersionless().getValue());
            } else if (entry.getRequest().getMethod() == Bundle.HTTPVerb.PUT) {
                // outcome = updateResource(resource);
                System.out.println("update resource :: PUT method called");
            } else {
                throw new UnprocessableEntityException("Unsupported HTTP verb: " + entry.getRequest().getMethod());
            }

            // Build response entry
            Bundle.BundleEntryComponent responseEntry = new Bundle.BundleEntryComponent();
            responseEntry.setResponse(new Bundle.BundleEntryResponseComponent()
                    .setStatus("201 Created")
                    .setLocation(outcome.getId().toUnqualifiedVersionless().getValue()));
            responseEntry.setResource((Resource) outcome.getResource());
            responseBundle.addEntry(responseEntry);
        }

        return responseBundle;
    }

    @SuppressWarnings("unchecked")
    private MethodOutcome createResource(Resource resource, String bucketName) {
        try {
            // Add audit information
            FhirAuditService auditService = new FhirAuditService();
            UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
            auditService.addAuditInfoToMeta(resource, auditInfo, "CREATE" , "1");

            if (resource instanceof DomainResource) {
                ((DomainResource) resource).getMeta().setLastUpdated(new Date());
            }

            // Validate the resource
            ValidationUtil validationUtil = new ValidationUtil();
            ValidationResult result = validationUtil.validate(resource, resource.getClass().getSimpleName(), fhirContext);
            if (!result.isSuccessful()) {
                StringBuilder issues = new StringBuilder();
                result.getMessages().forEach(msg -> issues.append(msg.getSeverity())
                        .append(": ")
                        .append(msg.getLocationString())
                        .append(" - ")
                        .append(msg.getMessage())
                        .append("\n"));

                throw new UnprocessableEntityException("FHIR Validation failed:\n" + issues.toString());
            }

            // Create the resource using the appropriate service
            @SuppressWarnings("unchecked")
            var dao = serviceFactory.getService((Class<Resource>) resource.getClass());
            var created = dao.create(resource.getClass().getSimpleName(), resource, bucketName)
                    .orElseThrow(() -> new InternalErrorException("Failed to create resource"));

            return new MethodOutcome(new IdType(resource.getClass().getSimpleName(), created.getIdElement().getIdPart()))
                    .setCreated(true)
                    .setResource(created);
        } catch (Exception e) {
            throw new InternalErrorException("Error creating resource: " + e.getMessage(), e);
        }
    }
}
