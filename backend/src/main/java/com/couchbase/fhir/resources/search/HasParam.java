package com.couchbase.fhir.resources.search;

public class HasParam {
     String targetResource;
     String referenceField;
     String criteriaParam;
     String criteriaValue;

    public HasParam(String targetResource, String referenceField, String criteriaParam, String criteriaValue) {
        this.targetResource = targetResource;
        this.referenceField = referenceField;
        this.criteriaParam = criteriaParam;
        this.criteriaValue = criteriaValue;
    }

    public static HasParam parse(String paramKey, String paramValue) {
        if (!paramKey.startsWith("_has:")) {
            return null;
        }

        try {
            String raw = paramKey.substring(5);
            String[] parts = raw.split(":");
            if (parts.length != 3) return null;
            return new HasParam(parts[0] , parts[1] , parts[2] , paramValue);
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "_has(" + targetResource + "->" + referenceField + " where "
                + criteriaParam + "=" + criteriaValue + ")";
    }

    public String getTargetResource() {
        return targetResource;
    }

    public void setTargetResource(String targetResource) {
        this.targetResource = targetResource;
    }

    public String getReferenceField() {
        return referenceField;
    }

    public void setReferenceField(String referenceField) {
        this.referenceField = referenceField;
    }

    public String getCriteriaParam() {
        return criteriaParam;
    }

    public void setCriteriaParam(String criteriaParam) {
        this.criteriaParam = criteriaParam;
    }

    public String getCriteriaValue() {
        return criteriaValue;
    }

    public void setCriteriaValue(String criteriaValue) {
        this.criteriaValue = criteriaValue;
    }
}
