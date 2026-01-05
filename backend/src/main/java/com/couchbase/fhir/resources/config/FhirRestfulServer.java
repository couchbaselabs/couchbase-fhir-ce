package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;

import com.couchbase.admin.users.bulkGroup.service.GroupAdminService;
import com.couchbase.fhir.resources.provider.BulkImportProvider;
import com.couchbase.fhir.resources.provider.USCoreCapabilityProvider;
import com.couchbase.fhir.resources.interceptor.BucketAwareValidationInterceptor;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.service.FtsSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Custom implementation of the HAPI FHIR {@link ca.uhn.fhir.rest.server.RestfulServer}
 * used to configure and bootstrap the FHIR server environment.
 *
 * This class is the central entry point for registering:
 * - Resource providers (e.g., PatientResourceProvider)
 * - System-level providers (e.g., CapabilityStatementProvider)
 * - Interceptors (logging, validation, CORS, etc.)
 * - FHIR context and default response formats
 *
 * It also sets the base path for FHIR endpoints and can be used to control
 * server behavior such as default encoding, error handling, paging, and more.
 *
 */

@RequiredArgsConstructor
@Component
public class FhirRestfulServer extends RestfulServer {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FhirRestfulServer.class);


    @Autowired
    private List<IResourceProvider> providers;
    
    @Autowired
    private ResourceProviderAutoConfig resourceProviderAutoConfig;


    
    @Autowired
    private FhirContext fhirContext; // ← Inject your configured context
    
    @Autowired
    private BucketAwareValidationInterceptor bucketValidationInterceptor;
    
    @Autowired
    private FhirBucketConfigService configService;
    
    @Autowired
    private com.couchbase.fhir.resources.interceptor.CleanExceptionInterceptor cleanExceptionInterceptor;
    
    @Autowired
    private com.couchbase.fhir.resources.interceptor.FastpathResponseInterceptor fastpathResponseInterceptor;
    
    @Autowired
    private com.couchbase.fhir.auth.SmartAuthorizationInterceptor smartAuthorizationInterceptor;
    
    @Autowired
    private com.couchbase.fhir.rest.interceptors.JwtValidationInterceptor jwtValidationInterceptor;
    
    @Autowired
    private com.couchbase.fhir.auth.GroupWriteBlockInterceptor groupWriteBlockInterceptor;
    
    @Autowired(required = false)
    private org.springframework.boot.info.BuildProperties buildProperties;
    
    @Autowired
    private com.couchbase.common.config.FhirServerConfig fhirServerConfig;

    @Autowired
    private com.couchbase.admin.connections.service.ConnectionService connectionService;

    @Autowired
    private FhirBundleProcessingService fhirBundleProcessingService;

    @Autowired
    private GroupAdminService groupAdminService;

    @Autowired
    private FtsSearchService ftsSearchService;

    @Override
    protected void initialize() {
        logger.info("🚀 Initializing FhirRestfulServer");
        
        // Debug: Log what providers we have from autowired list
        logger.info("Found {} providers in autowired list:", providers.size());
        for (IResourceProvider provider : providers) {
            logger.info("  - Provider: {} for resource: {}", 
                       provider.getClass().getSimpleName(), 
                       provider.getResourceType().getSimpleName());
        }
        
        // MANUAL FIX: Get dynamic providers directly from the config bean
        try {
            logger.info("🔧 Manually fetching dynamic providers from ResourceProviderAutoConfig...");
            List<IResourceProvider> dynamicProviders = resourceProviderAutoConfig.dynamicProviders();
            logger.info("📋 Got {} dynamic providers from config", dynamicProviders.size());
            
            // Combine autowired providers with dynamic providers
            List<IResourceProvider> allProviders = new java.util.ArrayList<>(providers);
            allProviders.addAll(dynamicProviders);
            
            logger.info("🎯 Total providers to register: {}", allProviders.size());
            // for (IResourceProvider provider : allProviders) {
            //     logger.info("  ✅ Will register: {} for resource: {}", 
            //                provider.getClass().getSimpleName(), 
            //                provider.getResourceType().getSimpleName());
            // }
            
            setFhirContext(fhirContext); // Use the injected context
            
            // Single-tenant mode: Use base URL from config.yaml (app.baseUrl)
            // This handles HAProxy SSL termination, reverse proxies, and SMART on FHIR correctly
            String configuredBaseUrl = fhirServerConfig.getNormalizedBaseUrl();
            logger.info("🌐 Server base URL configured from config.yaml: {}", configuredBaseUrl);
            
            setServerAddressStrategy(new ca.uhn.fhir.rest.server.HardcodedServerAddressStrategy(configuredBaseUrl));
            setPagingProvider(new ca.uhn.fhir.rest.server.FifoMemoryPagingProvider(100));
            
            // Single-tenant mode: TenantContextHolder always returns "fhir"
            // No interceptor needed for tenant identification
            
            registerInterceptor(bucketValidationInterceptor);
            registerInterceptor(smartAuthorizationInterceptor); // 🔐 SMART on FHIR authorization
            registerInterceptor(jwtValidationInterceptor); // 🔒 JWT token revocation check
            registerInterceptor(groupWriteBlockInterceptor); // 🚫 Block Group write operations via FHIR API
            registerInterceptor(cleanExceptionInterceptor);
            registerInterceptor(fastpathResponseInterceptor); // 🚀 Fastpath JSON bypass (10× memory reduction)
            USCoreCapabilityProvider capabilityProvider = new USCoreCapabilityProvider(this, buildProperties, configuredBaseUrl);
            setServerConformanceProvider(capabilityProvider);
            registerProviders(allProviders); // Register all providers
            registerProvider(new BulkImportProvider(fhirContext  , connectionService , fhirBundleProcessingService , groupAdminService , fhirServerConfig , ftsSearchService));


        } catch (Exception e) {
            logger.error("❌ Failed to get dynamic providers, falling back to autowired only: {}", e.getMessage());
            // Fallback to original behavior
            setFhirContext(fhirContext);
            
            // Single-tenant mode: Use base URL from config.yaml
            String configuredBaseUrl = fhirServerConfig.getNormalizedBaseUrl();
            logger.info("🌐 Server base URL configured from config.yaml: {} (fallback)", configuredBaseUrl);
            
            setServerAddressStrategy(new ca.uhn.fhir.rest.server.HardcodedServerAddressStrategy(configuredBaseUrl));
            setPagingProvider(new ca.uhn.fhir.rest.server.FifoMemoryPagingProvider(100));
            
            // Single-tenant mode: TenantContextHolder always returns "fhir"
            // No interceptor needed for tenant identification
            
            registerInterceptor(bucketValidationInterceptor);
            registerInterceptor(smartAuthorizationInterceptor); // 🔐 SMART on FHIR authorization
            registerInterceptor(jwtValidationInterceptor); // 🔒 JWT token revocation check
            registerInterceptor(groupWriteBlockInterceptor); // 🚫 Block Group write operations via FHIR API
            registerInterceptor(cleanExceptionInterceptor);
            registerInterceptor(fastpathResponseInterceptor); // 🚀 Fastpath JSON bypass (10× memory reduction)
            USCoreCapabilityProvider capabilityProvider = new USCoreCapabilityProvider(this, buildProperties, configuredBaseUrl);
            setServerConformanceProvider(capabilityProvider);
            registerProviders(providers);
        }
    }
}
