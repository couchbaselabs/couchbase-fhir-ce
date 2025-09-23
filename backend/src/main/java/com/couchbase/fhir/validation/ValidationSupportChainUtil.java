package com.couchbase.fhir.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.springframework.core.io.Resource;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;

public class ValidationSupportChainUtil {

    public static ValidationSupportChain buildValidationSupport(FhirContext fhirContext) {
        PrePopulatedValidationSupport usCoreSupport = new PrePopulatedValidationSupport(fhirContext);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        IParser parser = fhirContext.newJsonParser();

        try {
            // Load all JSON files from us_core folder
            Resource[] resources = resolver.getResources("classpath*:us_core_6.1.0/*.json");

            for (Resource res : resources) {
                try (InputStream stream = res.getInputStream()) {
                    StructureDefinition sd = parser.parseResource(StructureDefinition.class, stream);
                    usCoreSupport.addStructureDefinition(sd);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to load US Core profiles", e);
        }

        // Combine with other supports
        return new ValidationSupportChain(
                usCoreSupport,
                new DefaultProfileValidationSupport(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext)
        );
    }
}
