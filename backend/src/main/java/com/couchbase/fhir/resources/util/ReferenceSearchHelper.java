package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;

import java.util.Set;

public class ReferenceSearchHelper {

    public static SearchQuery buildReferenceFtsCluse(FhirContext fhirContext , String resourceType, String paramName, String value  , RuntimeSearchParam searchParam){
        String path = searchParam.getPath();
        String jsonPath = toCouchbasePath(path, resourceType);

        if (!value.contains("/")) {
            Set<String> targets = searchParam.getTargets();
            if (targets.size() == 1) {
                String targetResource = targets.iterator().next();
                value = targetResource + "/" + value;
            } else if (targets.size() > 1) {
                // Ambiguous target types — can't infer
                throw new IllegalArgumentException(
                        "The reference parameter '" + paramName + "' is ambiguous. " +
                                "Expected format: 'ResourceType/id', e.g., 'Patient/123'. " +
                                "Allowed targets: " + targets
                );
            }
        }
        return SearchQuery.match(value).field(jsonPath + ".reference");

    }



    private static String toCouchbasePath(String fhirPath, String resourceType) {
        if (fhirPath == null) {
            throw new IllegalArgumentException("FHIRPath is null ");
        }

        if (!fhirPath.startsWith(resourceType + ".") && !fhirPath.startsWith("Resource.")) {
            throw new IllegalArgumentException("Invalid FHIRPath: " + fhirPath);
        }

        String subPath = fhirPath.substring(resourceType.length() + 1);


        // Remove .where(...) clause if present
        int whereIndex = subPath.indexOf(".where(");
        if (whereIndex != -1) {
            subPath = subPath.substring(0, whereIndex);
        }

        String jsonPath = subPath
                .replace(".coding", "")
                .replace(".value", "")
                .replace(".code", "")
                .replace(".system", "");


        return jsonPath;
    }
}
