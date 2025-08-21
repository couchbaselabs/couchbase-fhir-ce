package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.*;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.search.model.ConceptInfo;
import com.couchbase.fhir.search.model.TokenParam;


import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TokenSearchHelperFTS {

    public static SearchQuery buildTokenFTSQuery(FhirContext fhirContext,
                                                 String resourceType,
                                                 String paramName,
                                                 String tokenValue) {

        TokenParam token = new TokenParam(tokenValue);
        boolean isMultipleValues = token.code.contains(",");

        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        RuntimeSearchParam searchParam = def.getSearchParam(paramName);
        String path = searchParam.getPath();

        ConceptInfo conceptInfo = getConceptInfo(path, resourceType, def);

        // FTS field names in index (assume "." in FHIRPath is replaced with "."
        String ftsFieldPath = toFTSFieldPath(path, resourceType, conceptInfo.isCodableConcept,
                conceptInfo.isArray, conceptInfo.isPrimitive);

        if (conceptInfo.isPrimitive) {
            // Primitive tokens like gender, id, status, etc.
            if (isMultipleValues) {
                List<SearchQuery> termQueries = Arrays.stream(token.code.split(","))
                        .map(String::trim)
                        .map(val -> SearchQuery.match(val).field(ftsFieldPath))
                        .collect(Collectors.toList());
                return SearchQuery.disjuncts(termQueries.toArray(new SearchQuery[0]));
            } else {
                return SearchQuery.match(token.code).field(ftsFieldPath);
            }
        }

        // Handle CodeableConcept arrays or single
        if (conceptInfo.isCodableConcept && conceptInfo.isArray) {
            // Example: ANY cat IN ... SATISFIES ANY coding...
            return buildCodeableConceptQuery(ftsFieldPath, token);
        } else if (conceptInfo.isCodableConcept) {
            // Single CodeableConcept
            return buildCodeableConceptQuery(ftsFieldPath, token);
        } else {
            // Other complex type with system/value
            return buildSystemValueQuery(ftsFieldPath, token);
        }
    }

    private static SearchQuery buildCodeableConceptQuery(String ftsFieldPath, TokenParam token) {
        // For FTS index: field might be like "name.coding.code" and "name.coding.system"
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(ftsFieldPath + ".coding.system"),
                    SearchQuery.match(token.code).field(ftsFieldPath + ".coding.code")
            );
        } else {
            return SearchQuery.match(token.code).field(ftsFieldPath + ".coding.code");
        }
    }

    private static SearchQuery buildSystemValueQuery(String ftsFieldPath, TokenParam token) {
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(ftsFieldPath + ".system"),
                    SearchQuery.match(token.code).field(ftsFieldPath + ".value")
            );
        } else {
            return SearchQuery.match(token.code).field(ftsFieldPath + ".value");
        }
    }

    public static ConceptInfo getConceptInfo(String path, String resourceType, RuntimeResourceDefinition def) {
        boolean isCodableConcept = false;
        boolean isArray = false;
        boolean isPrimitive = false;
        BaseRuntimeElementDefinition<?> current = def;
        try {
            String fhirPath = path.replaceFirst("^" + resourceType + "\\.", "");
            String[] pathParts = fhirPath.split("\\.");
            for (String part : pathParts) {
                if (def != null) {
                    BaseRuntimeChildDefinition child = ((BaseRuntimeElementCompositeDefinition<?>) def).getChildByName(part);
                    if (child != null) {
                        if (child.getMax() == -1) {
                            isArray = true;
                        }
                        if (child.getChildByName(part).getImplementingClass().getSimpleName().equalsIgnoreCase("CodeableConcept")) {
                            isCodableConcept = true;
                        }

                        current = child.getChildByName(part);
                        if (current.isStandardType() && !(current instanceof BaseRuntimeElementCompositeDefinition)) {
                            isPrimitive = true;
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        return new ConceptInfo(isCodableConcept, isArray, isPrimitive);
    }

    /**
     * Convert FHIRPath to the FTS field path used in the Couchbase index mapping.
     */
    private static String toFTSFieldPath(String fhirPath,
                                         String resourceType,
                                         boolean codableConcept,
                                         boolean isArray,
                                         boolean isPrimitive) {
        if (fhirPath == null) {
            throw new IllegalArgumentException("FHIRPath is null");
        }

        if (!fhirPath.startsWith(resourceType + ".") && !fhirPath.startsWith("Resource.")) {
            throw new IllegalArgumentException("Invalid FHIRPath: " + fhirPath);
        }
        String subPath;
        if (fhirPath.contains("Resource")) {
            subPath = fhirPath.substring(9);
        } else {
            subPath = fhirPath.substring(resourceType.length() + 1);
        }

        String ftsPath = subPath
                .replace(".coding", ".coding") // Keep .coding for FTS mapping
                .replace(".value", ".value")
                .replace(".code", ".code")
                .replace(".system", ".system");

        if (isPrimitive) {
            return ftsPath;
        }

        if (codableConcept && !isArray) {
            ftsPath += ".coding";
        }
        return ftsPath;
    }
}
