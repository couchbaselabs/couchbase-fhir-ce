package com.couchbase.fhir.auth.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for Patient Picker in Provider OAuth flow.
 * Provides search and display functionality for patient selection.
 */
@Service
public class PatientPickerService {

    private static final Logger logger = LoggerFactory.getLogger(PatientPickerService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ConnectionService connectionService;

    public PatientPickerService(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * Patient summary for picker display
     */
    public static class PatientSummary {
        private String id;
        private String givenName;
        private String familyName;
        private String birthDate;
        private String gender;
        private boolean deceased;

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getGivenName() { return givenName; }
        public void setGivenName(String givenName) { this.givenName = givenName; }
        
        public String getFamilyName() { return familyName; }
        public void setFamilyName(String familyName) { this.familyName = familyName; }
        
        public String getBirthDate() { return birthDate; }
        public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
        
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        
        public boolean isDeceased() { return deceased; }
        public void setDeceased(boolean deceased) { this.deceased = deceased; }
        
        public String getFullName() {
            return (givenName != null ? givenName + " " : "") + (familyName != null ? familyName : "");
        }
    }

    /**
     * Search for patients with optional search term (ID or name)
     * Uses FTS (Full-Text Search) for better performance without primary index
     * 
     * @param searchTerm Optional search term for filtering by ID
     * @param pageSize Number of results to return
     * @return List of patient summaries
     */
    public List<PatientSummary> searchPatients(String searchTerm, int pageSize) {
        try {
            Cluster cluster = connectionService.getConnection("default");
            StringBuilder query = new StringBuilder();
            int limit = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
            
            // If search term is provided, search by ID using USE KEYS
            if (searchTerm != null && !searchTerm.trim().isEmpty()) {
                searchTerm = searchTerm.trim();
                
                // Ensure proper Patient/ prefix
                String fullKey = searchTerm.startsWith("Patient/") ? searchTerm : "Patient/" + searchTerm;
                
                query.append("SELECT ");
                query.append("  p.id, ");
                query.append("  p.birthDate, ");
                query.append("  p.gender, ");
                query.append("  p.deceasedDateTime, ");
                query.append("  p.name[0].family AS family, ");
                query.append("  p.name[0].given[0] AS given ");
                query.append("FROM `").append(BUCKET_NAME).append("`.`Resources`.`Patient` AS p ");
                query.append("USE KEYS \"").append(fullKey).append("\"");
                
                logger.debug("🔍 Patient Picker Query (by ID): {}", query);
            } else {
                // No search term - use FTS to get all patients
                query.append("SELECT ");
                query.append("  p.id, ");
                query.append("  p.birthDate, ");
                query.append("  p.gender, ");
                query.append("  p.deceasedDateTime, ");
                query.append("  p.name[0].family AS family, ");
                query.append("  p.name[0].given[0] AS given ");
                query.append("FROM `").append(BUCKET_NAME).append("`.`Resources`.`Patient` AS p ");
                query.append("WHERE SEARCH(p, { \"query\": { \"match_all\": {} } }, ");
                query.append("{ \"index\": \"").append(BUCKET_NAME).append(".Resources.ftsPatient\" }) ");
                query.append("LIMIT ").append(limit);
                
                logger.debug("🔍 Patient Picker Query (FTS): {}", query);
            }
            
            QueryResult result = cluster.query(query.toString());
            List<PatientSummary> patients = new ArrayList<>();
            
            for (JsonObject row : result.rowsAsObject()) {
                PatientSummary patient = new PatientSummary();
                patient.setId(row.getString("id"));
                patient.setGivenName(row.getString("given"));
                patient.setFamilyName(row.getString("family"));
                patient.setBirthDate(row.getString("birthDate"));
                patient.setGender(row.getString("gender"));
                
                // Check if deceased
                Object deceased = row.get("deceasedDateTime");
                patient.setDeceased(deceased != null);
                
                patients.add(patient);
            }
            
            logger.info("✅ Found {} patients (search: '{}')", patients.size(), searchTerm);
            return patients;
            
        } catch (Exception e) {
            logger.error("❌ Error searching patients", e);
            throw new RuntimeException("Failed to search patients: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get a specific patient by ID for validation
     * Uses USE KEYS for direct document access
     */
    public PatientSummary getPatientById(String patientId) {
        try {
            // Ensure ID has Patient/ prefix
            String fullId = patientId.startsWith("Patient/") ? patientId : "Patient/" + patientId;
            
            Cluster cluster = connectionService.getConnection("default");
            String query = String.format(
                "SELECT p.id, p.name[0].given[0] as given, p.name[0].family as family, " +
                "p.birthDate, p.gender, p.deceasedDateTime " +
                "FROM `%s`.`Resources`.`Patient` AS p " +
                "USE KEYS \"%s\"",
                BUCKET_NAME, fullId
            );
            
            QueryResult result = cluster.query(query);
            if (!result.rowsAsObject().isEmpty()) {
                JsonObject row = result.rowsAsObject().get(0);
                PatientSummary patient = new PatientSummary();
                patient.setId(row.getString("id"));
                patient.setGivenName(row.getString("given"));
                patient.setFamilyName(row.getString("family"));
                patient.setBirthDate(row.getString("birthDate"));
                patient.setGender(row.getString("gender"));
                patient.setDeceased(row.get("deceasedDateTime") != null);
                return patient;
            }
            
            return null;
        } catch (Exception e) {
            logger.error("❌ Error fetching patient {}", patientId, e);
            return null;
        }
    }
}

