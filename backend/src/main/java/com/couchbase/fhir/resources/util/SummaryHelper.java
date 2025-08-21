package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.rest.api.SummaryEnum;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;


public class SummaryHelper {
    public static IBaseResource applySummary(JsonObject json, String summaryMode , FhirContext fhirContext) {
        if(summaryMode == null) {
            return keepAll(json,fhirContext);
        }
        return switch (summaryMode) {
            case "true" -> stripToMandatoryAndText(json, fhirContext);
            case "text" -> keepOnlyText(json, fhirContext);
            case "data" -> removeText(json, fhirContext);
            default -> keepAll(json, fhirContext);
        };
    }

    private static IBaseResource keepAll(JsonObject json, FhirContext fhirContext) {
        JsonParser parser = (JsonParser) fhirContext.newJsonParser();
        parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
        return parser.parseResource(json.toString());
    }

    private static IBaseResource stripToMandatoryAndText(JsonObject resource , FhirContext fhirContext) {
        JsonObject filtered = JsonObject.create();
        filtered.put("resourceType", resource.getString("resourceType"));
        filtered.put("id", resource.getString("id"));
        if (resource.containsKey("meta")) filtered.put("meta", resource.get("meta"));
        if (resource.containsKey("text")) filtered.put("text", resource.get("text"));

        JsonParser parser = (JsonParser) fhirContext.newJsonParser();
        parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
        return parser.parseResource(filtered.toString());
    }

    private static IBaseResource keepOnlyText(JsonObject resource , FhirContext fhirContext) {

        JsonObject filtered = JsonObject.create();
        filtered.put("resourceType", resource.getString("resourceType"));
        filtered.put("id", resource.getString("id"));
        if (resource.containsKey("text")) {
            filtered.put("text", resource.get("text"));
        }
        JsonParser parser = (JsonParser) fhirContext.newJsonParser();
        parser.setSummaryMode(true);
        parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
        return parser.parseResource(filtered.toString());
    }

    private static IBaseResource removeText(JsonObject obj ,  FhirContext fhirContext) {
        obj.removeKey("text");
        JsonParser parser = (JsonParser) fhirContext.newJsonParser();
        parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
        return parser.parseResource(obj.toString());
    }

}
