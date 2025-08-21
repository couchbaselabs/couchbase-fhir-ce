package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.model.VersionedResource;
import com.couchbase.fhir.resources.service.FhirAuditService;
import com.couchbase.fhir.resources.service.UserAuditInfo;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.time.Instant;

public class FhirVersioningUtil {

    public static VersionedResource getVersionedResource(JsonObject current , String resourceType , String currentId , String operation , FhirContext fhirContext) {
        FhirAuditService auditService = new FhirAuditService();
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
        String versionId = current.getObject("meta").getString("versionId");
        String historyKey = resourceType + "/" + currentId + "/" + versionId;
        String newKey = resourceType + "/" + currentId;

        JsonObject historyCopy = JsonObject.from(current.toMap());
        historyCopy.put("id", currentId + "/" + versionId);

        int nextVersion = Integer.parseInt(versionId) + 1;

        JsonObject newResource = JsonObject.from(current.toMap()).put("id", currentId);



        JsonParser parser = (JsonParser) fhirContext.newJsonParser();
        parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
        IBaseResource newIbaseResource = parser.parseResource(newResource.toString());
        auditService.addAuditInfoToMeta(newIbaseResource, auditInfo, operation , nextVersion+"");

        String json = fhirContext.newJsonParser().encodeResourceToString(newIbaseResource);
        JsonObject updatedResource = JsonObject.fromJson(json);

        if(operation.equalsIgnoreCase("DELETE")){
            updatedResource.put("deleted", true);
        }

        return new VersionedResource(historyKey, historyCopy, updatedResource, nextVersion ,newKey)  ;

    }
}
