package com.couchbase.fhir.resources.repository;

import ca.uhn.fhir.rest.param.DateParam;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;
import java.util.Optional;


public interface FhirResourceDao <T extends IBaseResource>{
    Optional<T> read(String resourceType, String id , String bucketName);
    Optional<T> readVersion(String resourceType, String versionId , String id , String bucketName);
    List<T> readMultiple(String resourceType, List<String> ids, String bucketName);
    Optional<T> create(String resourceType , T resource , String bucketName);
    List<T> history(String resourceType, String id , DateParam since, String bucketName);
}
