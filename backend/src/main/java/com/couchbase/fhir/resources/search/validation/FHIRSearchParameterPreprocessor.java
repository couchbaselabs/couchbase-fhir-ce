package com.couchbase.fhir.resources.search.validation;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.stereotype.Component;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * FHIR Search Parameter Preprocessor and Validator
 * Handles validation BEFORE query execution to catch FHIR compliance issues early
 * 
 * Key responsibilities:
 * - Validate multiple date parameters don't conflict (your specific use case)
 * - Check parameter existence for resource types
 * - Validate parameter value formats
 * - Detect logically impossible parameter combinations
 */
@Component
public class FhirSearchParameterPreprocessor {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirSearchParameterPreprocessor.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirOperationOutcomeBuilder outcomeBuilder;
    
    // FHIR prefix pattern for validation
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^(eq|ne|gt|lt|ge|le|sa|eb|ap)(.+)");
    
    /**
     * Main entry point for parameter preprocessing and validation
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @param allParams All query parameters with multiple values per key
     * @throws FhirSearchValidationException if validation fails
     */
    public void validateSearchParameters(String resourceType, Map<String, List<String>> allParams) {
        logger.info("🔍 Validating search parameters for {} - {} parameters", resourceType, allParams.size());
        
        try {
            // Step 1: Validate parameter existence and basic format
            validateParameterExistence(resourceType, allParams);
            
            // Step 2: Validate parameter value formats
            validateParameterFormats(resourceType, allParams);
            
            // Step 3: Validate parameter consistency (your main use case)
            validateParameterConsistency(resourceType, allParams);
            
            logger.info("✅ Parameter validation passed for {}", resourceType);
            
        } catch (FhirSearchValidationException e) {
            logger.warn("❌ Parameter validation failed: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * Validate that parameters exist for the resource type
     */
    private void validateParameterExistence(String resourceType, Map<String, List<String>> allParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        
        for (String paramName : allParams.keySet()) {
            // Skip control parameters and framework parameters
            if (paramName.startsWith("_") || isFrameworkParameter(paramName)) {
                continue;
            }
            
            RuntimeSearchParam searchParam = resourceDef.getSearchParam(paramName);
            if (searchParam == null) {
                logger.warn("Unknown parameter: {} for resource type: {}", paramName, resourceType);
                OperationOutcome outcome = outcomeBuilder.createUnsupportedParameterError(paramName, resourceType);
                throw new FhirSearchValidationException(
                    "Unknown search parameter: " + paramName, 
                    paramName, 
                    outcome
                );
            }
        }
    }
    
    /**
     * Validate parameter value formats
     */
    private void validateParameterFormats(String resourceType, Map<String, List<String>> allParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        
        for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
            String paramName = entry.getKey();
            List<String> values = entry.getValue();
            
            if (paramName.startsWith("_") || isFrameworkParameter(paramName)) {
                continue;
            }
            
            RuntimeSearchParam searchParam = resourceDef.getSearchParam(paramName);
            if (searchParam != null) {
                validateParameterValueFormats(searchParam, values, paramName);
            }
        }
    }
    
    /**
     * Validate individual parameter value formats based on FHIR type
     */
    private void validateParameterValueFormats(RuntimeSearchParam searchParam, List<String> values, String paramName) {
        RestSearchParameterTypeEnum paramType = searchParam.getParamType();
        
        for (String value : values) {
            try {
                switch (paramType) {
                    case DATE:
                        validateDateFormat(value, paramName);
                        break;
                    case NUMBER:
                        validateNumberFormat(value, paramName);
                        break;
                    case TOKEN:
                        validateTokenFormat(value, paramName);
                        break;
                    case REFERENCE:
                        validateReferenceFormat(value, paramName);
                        break;
                    case STRING:
                        validateStringFormat(value, paramName);
                        break;
                    // Add other types as needed
                }
            } catch (RuntimeException e) {
                OperationOutcome outcome = outcomeBuilder.createInvalidParameterValueError(
                    paramName, value, getExpectedFormat(paramType)
                );
                throw new FhirSearchValidationException(e.getMessage(), paramName, outcome);
            }
        }
    }
    
    /**
     * Validate parameter consistency - YOUR MAIN USE CASE
     * This catches conflicts like birthdate=1987-02-20&birthdate=1987-02-21
     */
    private void validateParameterConsistency(String resourceType, Map<String, List<String>> allParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        
        for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
            String paramName = entry.getKey();
            List<String> values = entry.getValue();
            
            if (paramName.startsWith("_") || isFrameworkParameter(paramName)) {
                continue;
            }
            
            RuntimeSearchParam searchParam = resourceDef.getSearchParam(paramName);
            if (searchParam != null && values.size() > 1) {
                validateMultipleParameterValues(searchParam, values, paramName);
            }
        }
    }
    
    /**
     * Validate multiple values for the same parameter - CORE LOGIC
     */
    private void validateMultipleParameterValues(RuntimeSearchParam searchParam, List<String> values, String paramName) {
        RestSearchParameterTypeEnum paramType = searchParam.getParamType();
        
        switch (paramType) {
            case DATE:
                validateMultipleDateValues(values, paramName);
                break;
            case NUMBER:
                validateMultipleNumberValues(values, paramName);
                break;
            case TOKEN:
                // TOKEN parameters with multiple values are usually OR logic (valid)
                // e.g., gender=male,female would find male OR female patients
                validateMultipleTokenValues(values, paramName);
                break;
            case REFERENCE:
                // REFERENCE parameters with multiple values are usually OR logic (valid)
                break;
            case STRING:
                // STRING parameters with multiple values are usually OR logic (valid)
                break;
            // Other types typically allow multiple values as OR logic
        }
    }
    
    /**
     * Validate multiple date values - YOUR SPECIFIC CASE
     * The rule: Multiple date parameters without explicit prefixes are invalid
     * because a birthdate can't be two different values simultaneously
     */
    private void validateMultipleDateValues(List<String> values, String paramName) {
        logger.debug("Validating multiple date values for {}: {}", paramName, values);
        
        List<String> implicitEqualityValues = new ArrayList<>();
        List<String> explicitPrefixValues = new ArrayList<>();
        
        // Separate implicit equality (no prefix) from explicit prefix values
        for (String value : values) {
            if (hasExplicitPrefix(value)) {
                explicitPrefixValues.add(value);
            } else {
                implicitEqualityValues.add(value);
            }
        }
        
        // Rule 1: Multiple implicit equality values are INVALID
        // e.g., birthdate=1987-02-20&birthdate=1987-02-21
        if (implicitEqualityValues.size() > 1) {
            logger.warn("❌ Multiple implicit equality date values detected: {}", implicitEqualityValues);
            OperationOutcome outcome = outcomeBuilder.createConflictingDateParametersError(paramName);
            throw new FhirSearchValidationException(
                "Can not have multiple date range parameters for the same param without a qualifier", 
                paramName, 
                outcome
            );
        }
        
        // Rule 2: Mixed implicit and explicit values are INVALID
        // e.g., birthdate=1987-02-20&birthdate=ge1987-01-01
        if (!implicitEqualityValues.isEmpty() && !explicitPrefixValues.isEmpty()) {
            logger.warn("❌ Mixed implicit and explicit date values detected");
            OperationOutcome outcome = outcomeBuilder.createConflictingDateParametersError(paramName);
            throw new FhirSearchValidationException(
                "Can not have multiple date range parameters for the same param without a qualifier", 
                paramName, 
                outcome
            );
        }
        
        // Rule 3: Multiple explicit prefix values might be valid (ranges)
        // e.g., birthdate=ge1987-01-01&birthdate=le1987-12-31 (born in 1987)
        // We could add range overlap validation here if needed
        if (explicitPrefixValues.size() > 1) {
            logger.debug("Multiple explicit prefix date values (might be valid range): {}", explicitPrefixValues);
            // For now, we allow this - could add range conflict detection later
        }
    }
    
    /**
     * Validate multiple number values (similar logic to dates)
     */
    private void validateMultipleNumberValues(List<String> values, String paramName) {
        List<String> implicitEqualityValues = new ArrayList<>();
        
        for (String value : values) {
            if (!hasExplicitPrefix(value)) {
                implicitEqualityValues.add(value);
            }
        }
        
        // Multiple implicit equality number values are likely invalid
        if (implicitEqualityValues.size() > 1) {
            logger.warn("❌ Multiple implicit equality number values detected: {}", implicitEqualityValues);
            OperationOutcome outcome = outcomeBuilder.createParameterValidationError(paramName,
                "Multiple values without explicit prefixes not allowed for numeric parameters");
            throw new FhirSearchValidationException(
                "Multiple number values without prefixes", paramName, outcome
            );
        }
    }
    
    /**
     * Validate multiple token values
     */
    private void validateMultipleTokenValues(List<String> values, String paramName) {
        // For certain single-value fields, multiple different values are conflicting
        if (isSingleValueTokenField(paramName)) {
            Set<String> distinctCodes = new HashSet<>();
            for (String value : values) {
                String code = extractTokenCode(value);
                distinctCodes.add(code);
            }
            
            if (distinctCodes.size() > 1) {
                logger.warn("❌ Multiple conflicting token values for single-value field {}: {}", paramName, values);
                OperationOutcome outcome = outcomeBuilder.createParameterValidationError(paramName,
                    "Multiple conflicting values not allowed for single-value field");
                throw new FhirSearchValidationException(
                    "Conflicting token values for single-value field", paramName, outcome
                );
            }
        }
    }
    
    // ========== Format Validation Methods ==========
    
    private void validateDateFormat(String value, String paramName) {
        try {
            String dateValue = value.replaceFirst("^(eq|ne|gt|lt|ge|le|sa|eb|ap)", "");
            
            if (dateValue.length() == 10) {
                // Date only format: YYYY-MM-DD
                LocalDate.parse(dateValue, DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                // DateTime format: YYYY-MM-DDTHH:MM:SS[.mmm][Z|±HH:MM]
                Instant.parse(dateValue);
            }
        } catch (Exception e) {
            throw new RuntimeException("Invalid date format: " + value);
        }
    }
    
    private void validateNumberFormat(String value, String paramName) {
        try {
            String numberValue = value.replaceFirst("^(eq|ne|gt|lt|ge|le|ap)", "");
            Double.parseDouble(numberValue);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number format: " + value);
        }
    }
    
    private void validateTokenFormat(String value, String paramName) {
        // Token can be: code, system|code, |code, system|
        if (value.contains("|")) {
            String[] parts = value.split("\\|", 2);
            // Basic validation - ensure no invalid characters
            if (parts[0].trim().isEmpty() && parts[1].trim().isEmpty()) {
                throw new RuntimeException("Invalid token format: " + value);
            }
        }
    }
    
    private void validateReferenceFormat(String value, String paramName) {
        // Reference can be: ResourceType/id, relative/absolute URL, or just id
        if (value.contains("/")) {
            String[] parts = value.split("/", 2);
            if (parts.length != 2 || parts[1].trim().isEmpty()) {
                throw new RuntimeException("Invalid reference format: " + value);
            }
        }
    }
    
    private void validateStringFormat(String value, String paramName) {
        if (value.trim().isEmpty()) {
            throw new RuntimeException("Empty string value for parameter " + paramName);
        }
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Check if a value has an explicit FHIR prefix
     */
    private boolean hasExplicitPrefix(String value) {
        return PREFIX_PATTERN.matcher(value).matches();
    }
    
    /**
     * Check if parameter is a framework parameter (like connectionName, bucketName)
     */
    private boolean isFrameworkParameter(String paramName) {
        return paramName.equals("connectionName") || paramName.equals("bucketName");
    }
    
    /**
     * Check if a token field should only have single values
     */
    private boolean isSingleValueTokenField(String paramName) {
        Set<String> singleValueFields = Set.of("gender", "active", "deceased");
        return singleValueFields.contains(paramName);
    }
    
    /**
     * Extract code part from token value (system|code -> code)
     */
    private String extractTokenCode(String value) {
        if (value.contains("|")) {
            String[] parts = value.split("\\|", 2);
            return parts[1];
        }
        return value;
    }
    
    /**
     * Get expected format description for parameter type
     */
    private String getExpectedFormat(RestSearchParameterTypeEnum paramType) {
        switch (paramType) {
            case DATE: return "YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS[Z], optionally prefixed with eq|ne|gt|lt|ge|le|sa|eb";
            case NUMBER: return "Numeric value, optionally prefixed with eq|ne|gt|lt|ge|le";
            case TOKEN: return "code or system|code";
            case REFERENCE: return "ResourceType/id or relative/absolute URL";
            case STRING: return "Text string";
            default: return "Valid " + paramType.name().toLowerCase() + " value";
        }
    }
} 