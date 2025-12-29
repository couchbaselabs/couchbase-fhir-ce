package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.util.TokenSearchHelper;
import com.couchbase.fhir.resources.util.StringSearchHelper;
import com.couchbase.fhir.resources.util.DateSearchHelper;
import com.couchbase.fhir.resources.util.ReferenceSearchHelper;
import com.couchbase.fhir.resources.util.QuantitySearchHelper;
import com.couchbase.fhir.resources.util.USCoreSearchHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds FTS SearchQuery objects from resolved FHIR search parameters.
 * 
 * This service takes ResolvedParameter objects (from FhirSearchParameterResolver)
 * and converts them into Couchbase FTS queries using type-specific helpers.
 * 
 * Responsibilities:
 * - Delegate to type-specific query builders (TokenSearchHelper, StringSearchHelper, etc.)
 * - Handle modifiers (:exact, :contains, etc.)
 * - Support both HAPI and US Core parameters
 * - Build conjuncts/disjuncts for complex queries
 */
@Service
public class FtsQueryBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsQueryBuilder.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    /**
     * Build FTS queries for a single resolved parameter with its values.
     * 
     * @param resolved The resolved parameter (from FhirSearchParameterResolver)
     * @param values List of values for this parameter (supports OR logic for multiple values)
     * @return List of SearchQuery objects (may be multiple for complex parameters)
     */
    public List<SearchQuery> buildQueries(FhirSearchParameterResolver.ResolvedParameter resolved, 
                                         List<String> values) {
        
        if (resolved == null || values == null || values.isEmpty()) {
            logger.warn("Cannot build query: resolved={}, values={}", resolved, values);
            return List.of();
        }
        
        logger.debug("🔨 Building FTS queries for parameter: {} (type: {}, values: {})", 
                   resolved.getName(), resolved.getParamType(), values.size());
        
        List<SearchQuery> queries = new ArrayList<>();
        
        // Route to appropriate builder based on parameter type
        String paramType = resolved.getParamType();
        
        switch (paramType) {
            case "TOKEN":
                queries.addAll(buildTokenQueries(resolved, values));
                break;
                
            case "STRING":
                queries.addAll(buildStringQueries(resolved, values));
                break;
                
            case "DATE":
                queries.addAll(buildDateQueries(resolved, values));
                break;
                
            case "REFERENCE":
                queries.addAll(buildReferenceQueries(resolved, values));
                break;
                
            case "QUANTITY":
            case "COMPOSITE":
            case "URI":
                queries.addAll(buildQuantityQueries(resolved, values));
                break;
                
            case "NUMBER":
            case "SPECIAL":
                logger.warn("Unsupported search parameter type: {} for parameter: {}", 
                          paramType, resolved.getName());
                break;
                
            default:
                logger.warn("Unknown search parameter type: {} for parameter: {}", 
                          paramType, resolved.getName());
        }
        
        logger.debug("🔨 Built {} FTS queries for parameter {}", queries.size(), resolved.getName());
        return queries;
    }
    
    /**
     * Build TOKEN queries (identifier, code, boolean, etc.)
     */
    private List<SearchQuery> buildTokenQueries(FhirSearchParameterResolver.ResolvedParameter resolved, 
                                               List<String> values) {
        List<SearchQuery> queries = new ArrayList<>();
        
        for (String value : values) {
            try {
                SearchQuery query;
                
                if (resolved.isFromUSCore()) {
                    // Use US Core helper
                    List<SearchQuery> usCoreQueries = USCoreSearchHelper.buildUSCoreFTSQueries(
                        fhirContext, 
                        resolved.getResourceType(), 
                        resolved.getName(), 
                        List.of(value), 
                        resolved.getUsCoreSearchParam()
                    );
                    
                    if (usCoreQueries != null && !usCoreQueries.isEmpty()) {
                        queries.addAll(usCoreQueries);
                        logger.debug("🔨 Added {} US Core TOKEN queries for {}", 
                                   usCoreQueries.size(), resolved.getName());
                    }
                } else {
                    // Use HAPI helper
                    query = TokenSearchHelper.buildTokenFTSQuery(
                        fhirContext, 
                        resolved.getResourceType(), 
                        resolved.getName(), 
                        value
                    );
                    
                    if (query != null) {
                        queries.add(query);
                        logger.debug("🔨 Added TOKEN query for {}: {}", resolved.getName(), query.export());
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to build TOKEN query for {} = {}: {}", 
                           resolved.getName(), value, e.getMessage());
            }
        }
        
        return queries;
    }
    
    /**
     * Build STRING queries (name, address, text search, etc.)
     */
    private List<SearchQuery> buildStringQueries(FhirSearchParameterResolver.ResolvedParameter resolved, 
                                                 List<String> values) {
        List<SearchQuery> queries = new ArrayList<>();
        
        for (String value : values) {
            try {
                RuntimeSearchParam searchParam = resolved.getRuntimeSearchParam();
                
                SearchQuery query = StringSearchHelper.buildStringFTSQuery(
                    fhirContext, 
                    resolved.getResourceType(), 
                    resolved.getName(), 
                    value, 
                    searchParam, 
                    resolved.getModifier()
                );
                
                if (query != null) {
                    queries.add(query);
                    logger.debug("🔨 Added STRING query for {}: {}", resolved.getName(), query.export());
                }
            } catch (Exception e) {
                logger.error("Failed to build STRING query for {} = {}: {}", 
                           resolved.getName(), value, e.getMessage());
            }
        }
        
        return queries;
    }
    
    /**
     * Build DATE queries (birthdate, date ranges, etc.)
     */
    private List<SearchQuery> buildDateQueries(FhirSearchParameterResolver.ResolvedParameter resolved, 
                                               List<String> values) {
        List<SearchQuery> queries = new ArrayList<>();
        
        try {
            SearchQuery query = DateSearchHelper.buildDateFTS(
                fhirContext, 
                resolved.getResourceType(), 
                resolved.getName(), 
                values
            );
            
            if (query != null) {
                queries.add(query);
                logger.debug("🔨 Added DATE query for {}: {}", resolved.getName(), query.export());
            }
        } catch (Exception e) {
            logger.error("Failed to build DATE query for {}: {}", resolved.getName(), e.getMessage());
        }
        
        return queries;
    }
    
    /**
     * Build REFERENCE queries (subject, patient, etc.)
     */
    private List<SearchQuery> buildReferenceQueries(FhirSearchParameterResolver.ResolvedParameter resolved, 
                                                    List<String> values) {
        List<SearchQuery> queries = new ArrayList<>();
        
        for (String value : values) {
            try {
                RuntimeSearchParam searchParam = resolved.getRuntimeSearchParam();
                
                SearchQuery query = ReferenceSearchHelper.buildReferenceFTSQuery(
                    fhirContext, 
                    resolved.getResourceType(), 
                    resolved.getName(), 
                    value, 
                    searchParam
                );
                
                if (query != null) {
                    queries.add(query);
                    logger.debug("🔨 Added REFERENCE query for {}: {}", resolved.getName(), query.export());
                } else {
                    logger.warn("Failed to build REFERENCE query for {} = {}", resolved.getName(), value);
                }
            } catch (Exception e) {
                logger.error("Failed to build REFERENCE query for {} = {}: {}", 
                           resolved.getName(), value, e.getMessage());
            }
        }
        
        return queries;
    }
    
    /**
     * Build QUANTITY queries (value with units, ranges, etc.)
     */
    private List<SearchQuery> buildQuantityQueries(FhirSearchParameterResolver.ResolvedParameter resolved, 
                                                   List<String> values) {
        List<SearchQuery> queries = new ArrayList<>();
        
        for (String value : values) {
            try {
                RuntimeSearchParam searchParam = resolved.getRuntimeSearchParam();
                
                SearchQuery query = QuantitySearchHelper.buildQuantityFTSQuery(
                    fhirContext, 
                    resolved.getResourceType(), 
                    resolved.getName(), 
                    value, 
                    searchParam
                );
                
                if (query != null) {
                    queries.add(query);
                    logger.debug("🔨 Added QUANTITY query for {}: {}", resolved.getName(), query.export());
                }
            } catch (Exception e) {
                logger.error("Failed to build QUANTITY query for {} = {}: {}", 
                           resolved.getName(), value, e.getMessage());
            }
        }
        
        return queries;
    }
    
    /**
     * Build a combined query from multiple parameter queries.
     * Uses AND logic (conjuncts) to combine different parameters.
     * 
     * @param parameterQueries Map of parameter name to its list of queries
     * @return Combined SearchQuery, or matchAll if no queries
     */
    public SearchQuery buildCombinedQuery(List<SearchQuery> allQueries) {
        if (allQueries == null || allQueries.isEmpty()) {
            return SearchQuery.matchAll();
        } else if (allQueries.size() == 1) {
            return allQueries.get(0);
        } else {
            // Use AND logic to combine all queries
            return SearchQuery.conjuncts(allQueries.toArray(new SearchQuery[0]));
        }
    }
}

