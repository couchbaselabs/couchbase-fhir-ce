package com.couchbase.fhir.resources.repository;

import com.couchbase.client.java.json.JsonObject;
import com.couchbase.fhir.resources.model.VersionedResource;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.Optional;


public interface FhirResourceDao <T extends IBaseResource>{
    JsonObject read(String resourceType, String id , String bucketName);
    Optional<T> create(String resourceType , T resource , String bucketName);
    boolean upsert(String resourceType , VersionedResource versionedResource ,  String bucketName);

}
