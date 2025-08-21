package com.couchbase.fhir.resources.util;

import com.couchbase.client.java.search.SearchQuery;

import java.util.ArrayList;
import java.util.List;

public class RevIncludeHelperFTS {
    public static SearchQuery buildRevIncludeFTSQuery(
            List<String> referenceFields,
            List<String> baseReferences
    ) {

        List<SearchQuery> queries = new ArrayList<>();

        if (baseReferences == null || baseReferences.isEmpty()) {
            return null;
        }

        for(String baseReference : baseReferences){
            String revInclude = referenceFields.get(0);
            String[] revParts = revInclude.split(":");
            String referenceField = revParts[1];
            queries.add(SearchQuery.match(baseReference).field(referenceField+".reference"));
        }

        return SearchQuery.disjuncts(
                queries.toArray(new SearchQuery[0])
        );
    }
}
