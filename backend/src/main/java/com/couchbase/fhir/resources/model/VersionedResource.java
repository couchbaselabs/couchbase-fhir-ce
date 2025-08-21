package com.couchbase.fhir.resources.model;

import com.couchbase.client.java.json.JsonObject;

public class VersionedResource {
    public final String historyKey;
    public final JsonObject historyCopy;
    public final JsonObject newResource;
    public final String newKey;
    public final int nextVersion;

    public VersionedResource(String historyKey, JsonObject historyCopy, JsonObject newResource, int nextVersion , String newKey) {
        this.historyKey = historyKey;
        this.historyCopy = historyCopy;
        this.newResource = newResource;
        this.nextVersion = nextVersion;
        this.newKey = newKey;
    }
}
