package com.couchbase.fhir.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for validating FHIR resources against US Core StructureDefinitions.
 *
 * <p>This class provides a method to perform schema and schematron validation
 * on any FHIR {@link Resource}, using a corresponding custom StructureDefinition
 * loaded from the application's classpath (typically under {@code /us_core/}).</p>
 *
 * <p>The validation is performed using HAPI FHIR's {@link FhirValidator} and
 * {@link FhirInstanceValidator}, with a {@link ValidationSupportChain} combining
 * preloaded profiles and default validation support.</p>
 */

public class ValidationUtil {


    public  ValidationResult validate(Resource resource  , String resourceName, FhirContext fhirContext) {

        FhirValidator validator = fhirContext.newValidator();
        validator.setValidateAgainstStandardSchema(true);
        validator.setValidateAgainstStandardSchematron(true);
        ValidationSupportChain chain = ValidationSupportChainUtil.buildValidationSupport(fhirContext);
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(chain);
        validator.registerValidatorModule(instanceValidator);
        return validator.validateWithResult(resource);
    }


    public void validateDateParams(Map<String, String[]> searchParams , RuntimeResourceDefinition def){
        Map<String, Boolean> hasUnqualifiedMap = new HashMap<>();
        Map<String, Boolean> hasLowerBoundMap = new HashMap<>();
        Map<String, Boolean> hasUpperBoundMap = new HashMap<>();

        for (Map.Entry<String, String[]> entry : searchParams.entrySet()) {
            String rawParam = entry.getKey();
            String baseParam = rawParam.contains(":") ? rawParam.substring(0, rawParam.indexOf(':')) : rawParam;

            RuntimeSearchParam searchParam = def.getSearchParam(baseParam);

            if (searchParam.getParamType() == RestSearchParameterTypeEnum.DATE){
                boolean hasUnqualified = hasUnqualifiedMap.getOrDefault(baseParam, false);
                boolean hasLower = hasLowerBoundMap.getOrDefault(baseParam, false);
                boolean hasUpper = hasUpperBoundMap.getOrDefault(baseParam, false);


                for (String val : entry.getValue()) {
                    DateParam dateParam = new DateParam(val);
                    ParamPrefixEnum prefix = dateParam.getPrefix();

                    if (prefix == null) {
                        if (hasUnqualified) throw new IllegalArgumentException(" Can not have multiple unqualified date range parameters for the same param " + baseParam);
                        hasUnqualified = true;
                    } else if (prefix == ParamPrefixEnum.GREATERTHAN_OR_EQUALS || prefix == ParamPrefixEnum.GREATERTHAN) {
                        if (hasLower) throw new IllegalArgumentException(" Can not have multiple lower bound date range parameters for the same param " + baseParam);
                        hasLower = true;
                    } else if (prefix == ParamPrefixEnum.LESSTHAN_OR_EQUALS || prefix == ParamPrefixEnum.LESSTHAN) {
                        if (hasUpper) throw new IllegalArgumentException(" Can not have multiple upper bound date range parameters for the same param  " + baseParam);
                        hasUpper = true;
                    }
                }


                hasUnqualifiedMap.put(baseParam, hasUnqualified);
                hasLowerBoundMap.put(baseParam, hasLower);
                hasUpperBoundMap.put(baseParam, hasUpper);
            }


        }
    }


}
