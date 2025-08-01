package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import com.couchbase.fhir.resources.provider.FhirCouchbaseResourceProvider;
import com.couchbase.fhir.resources.service.FHIRResourceService;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Auto-configuration class that dynamically registers FHIR {@link IResourceProvider} instances
 * for all available FHIR R4 resource types at application startup.
 *
 * <p>This class scans all FHIR R4 resource definitions using the {@link FhirContext} and
 * creates a generic {@link FhirCouchbaseResourceProvider} for each resource. These providers
 * are then registered as Spring beans to support RESTful interactions with all FHIR resources
 * without the need to define them individually.</p>
 *
 * <p>The {@link FHIRResourceService} is used as a factory to provide data access services
 * for each FHIR resource type, allowing for dynamic and scalable backend integration
 * (e.g., Couchbase in this case).</p>
 */

@Component
public class ResourceProviderAutoConfig {

    @Autowired

    private FHIRResourceService serviceFactory;

    @SuppressWarnings("unchecked")
    @Bean
    public List<IResourceProvider> dynamicProviders() {
        List<IResourceProvider> providers = new ArrayList<>();
        FhirContext fhirContext = FhirContext.forR4();
        return fhirContext.getResourceTypes().stream()
                .map(fhirContext::getResourceDefinition)
                .map(rd -> (Class<? extends Resource>) rd.getImplementingClass())
                .distinct()
                .map(clazz -> new FhirCouchbaseResourceProvider<>(clazz, serviceFactory.getService(clazz) , fhirContext))
                .collect(Collectors.toList());

    }
}
