package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.common.config.FhirConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves FHIR search parameters to FTS-queryable paths and types.
 * Handles parameter resolution from multiple sources:
 * 1. HAPI FHIR base definitions (R4 spec)
 * 2. US Core Implementation Guide extensions
 * 3. Future: Custom IGs and organizational profiles
 * 
 * This service provides a single source of truth for "what does this parameter mean?"
 * regardless of whether it's used by SearchService, FtsGroupService, or other consumers.
 */
@Service
public class FhirSearchParameterResolver {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirSearchParameterResolver.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirConfig fhirConfig;
    
    /**
     * Resolve a FHIR search parameter to its queryable components.
     * 
     * Resolution order:
     * 1. Try HAPI FHIR definitions (base R4 spec)
     * 2. Try US Core extensions (if enabled)
     * 3. Return null if parameter is unknown
     * 
     * @param resourceType FHIR resource type (e.g., "Patient", "Observation")
     * @param rawParamName Raw parameter name (may include modifiers like "name:exact")
     * @return ResolvedParameter containing path, type, and metadata, or null if not found
     */
    public ResolvedParameter resolve(String resourceType, String rawParamName) {
        // Parse parameter name and modifier
        String paramName = rawParamName;
        String modifier = null;
        
        int colonIndex = rawParamName.indexOf(':');
        if (colonIndex != -1) {
            paramName = rawParamName.substring(0, colonIndex);
            modifier = rawParamName.substring(colonIndex + 1);
        }
        
        logger.debug("🔍 Resolving parameter: {}:{} for {} (modifier: {})", 
                   paramName, modifier != null ? modifier : "none", resourceType, modifier);
        
        // Step 1: Try HAPI FHIR base definitions
        ResolvedParameter hapiResolved = resolveFromHapi(resourceType, paramName, modifier);
        if (hapiResolved != null) {
            logger.debug("✅ Resolved from HAPI: {} -> type={}, path={}", 
                       paramName, hapiResolved.getParamType(), hapiResolved.getPath());
            return hapiResolved;
        }
        
        // Step 2: Try US Core extensions
        ResolvedParameter usCoreResolved = resolveFromUSCore(resourceType, paramName, modifier);
        if (usCoreResolved != null) {
            logger.debug("✅ Resolved from US Core: {} -> type={}, expression={}", 
                       paramName, usCoreResolved.getParamType(), usCoreResolved.getExpression());
            return usCoreResolved;
        }
        
        // Step 3: Not found
        logger.warn("❌ Unknown search parameter: {} for resource type {}", rawParamName, resourceType);
        return null;
    }
    
    /**
     * Resolve parameter from HAPI FHIR base definitions (R4 spec)
     */
    private ResolvedParameter resolveFromHapi(String resourceType, String paramName, String modifier) {
        try {
            RuntimeSearchParam searchParam = fhirContext
                    .getResourceDefinition(resourceType)
                    .getSearchParam(paramName);
            
            if (searchParam != null) {
                return ResolvedParameter.builder()
                        .name(paramName)
                        .modifier(modifier)
                        .paramType(searchParam.getParamType().name())
                        .path(searchParam.getPath())
                        .resourceType(resourceType)
                        .source("HAPI")
                        .runtimeSearchParam(searchParam)
                        .build();
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve from HAPI: {} - {}", paramName, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Resolve parameter from US Core Implementation Guide
     */
    private ResolvedParameter resolveFromUSCore(String resourceType, String paramName, String modifier) {
        try {
            // Check if this is a valid US Core parameter
            boolean isUSCoreParam = fhirConfig.isValidUSCoreSearchParam(resourceType, paramName);
            if (!isUSCoreParam) {
                return null;
            }
            
            // Get detailed parameter definition
            org.hl7.fhir.r4.model.SearchParameter usCoreParam = 
                fhirConfig.getUSCoreSearchParamDetails(resourceType, paramName);
            
            if (usCoreParam != null) {
                logger.debug("🔍 Found US Core parameter: {} for {}", paramName, resourceType);
                logger.debug("   - Code: {}", usCoreParam.getCode());
                logger.debug("   - Type: {}", usCoreParam.getType());
                logger.debug("   - Expression: {}", usCoreParam.getExpression());
                
                return ResolvedParameter.builder()
                        .name(paramName)
                        .modifier(modifier)
                        .paramType(usCoreParam.getType().name())
                        .expression(usCoreParam.getExpression())
                        .resourceType(resourceType)
                        .source("US_CORE")
                        .usCoreSearchParam(usCoreParam)
                        .build();
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve from US Core: {} - {}", paramName, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Container for resolved parameter information.
     * Provides all information needed to build FTS queries.
     */
    public static class ResolvedParameter {
        private final String name;
        private final String modifier;
        private final String paramType;        // TOKEN, STRING, DATE, REFERENCE, etc.
        private final String path;             // FHIRPath expression (from HAPI)
        private final String expression;       // FHIRPath expression (from SearchParameter)
        private final String resourceType;
        private final String source;           // HAPI, US_CORE, CUSTOM, etc.
        
        // Source-specific metadata (for advanced query building)
        private final RuntimeSearchParam runtimeSearchParam;  // For HAPI parameters
        private final org.hl7.fhir.r4.model.SearchParameter usCoreSearchParam;  // For US Core
        
        private ResolvedParameter(Builder builder) {
            this.name = builder.name;
            this.modifier = builder.modifier;
            this.paramType = builder.paramType;
            this.path = builder.path;
            this.expression = builder.expression;
            this.resourceType = builder.resourceType;
            this.source = builder.source;
            this.runtimeSearchParam = builder.runtimeSearchParam;
            this.usCoreSearchParam = builder.usCoreSearchParam;
        }
        
        // Getters
        public String getName() { return name; }
        public String getModifier() { return modifier; }
        public String getParamType() { return paramType; }
        public String getPath() { return path; }
        public String getExpression() { return expression; }
        public String getResourceType() { return resourceType; }
        public String getSource() { return source; }
        public RuntimeSearchParam getRuntimeSearchParam() { return runtimeSearchParam; }
        public org.hl7.fhir.r4.model.SearchParameter getUsCoreSearchParam() { return usCoreSearchParam; }
        
        public boolean hasModifier() { return modifier != null && !modifier.isEmpty(); }
        public boolean isFromHapi() { return "HAPI".equals(source); }
        public boolean isFromUSCore() { return "US_CORE".equals(source); }
        
        /**
         * Get the FHIRPath expression (prioritize 'path' from HAPI, fall back to 'expression')
         */
        public String getFhirPath() {
            return path != null ? path : expression;
        }
        
        @Override
        public String toString() {
            return String.format("ResolvedParameter{name='%s', type='%s', source='%s', path='%s'}", 
                               name, paramType, source, getFhirPath());
        }
        
        // Builder pattern
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String name;
            private String modifier;
            private String paramType;
            private String path;
            private String expression;
            private String resourceType;
            private String source;
            private RuntimeSearchParam runtimeSearchParam;
            private org.hl7.fhir.r4.model.SearchParameter usCoreSearchParam;
            
            public Builder name(String name) {
                this.name = name;
                return this;
            }
            
            public Builder modifier(String modifier) {
                this.modifier = modifier;
                return this;
            }
            
            public Builder paramType(String paramType) {
                this.paramType = paramType;
                return this;
            }
            
            public Builder path(String path) {
                this.path = path;
                return this;
            }
            
            public Builder expression(String expression) {
                this.expression = expression;
                return this;
            }
            
            public Builder resourceType(String resourceType) {
                this.resourceType = resourceType;
                return this;
            }
            
            public Builder source(String source) {
                this.source = source;
                return this;
            }
            
            public Builder runtimeSearchParam(RuntimeSearchParam runtimeSearchParam) {
                this.runtimeSearchParam = runtimeSearchParam;
                return this;
            }
            
            public Builder usCoreSearchParam(org.hl7.fhir.r4.model.SearchParameter usCoreSearchParam) {
                this.usCoreSearchParam = usCoreSearchParam;
                return this;
            }
            
            public ResolvedParameter build() {
                return new ResolvedParameter(this);
            }
        }
    }
}

