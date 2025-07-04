# Sample Data Feature

This feature provides functionality to load sample FHIR data into Couchbase buckets for demonstration and testing purposes.

## API Endpoints

### POST `/api/sample-data/load`

Load sample FHIR data into a specified bucket.

**Request Body:**

```json
{
  "connectionName": "string",
  "bucketName": "string",
  "overwriteExisting": boolean (optional, default: false)
}
```

**Response:**

```json
{
  "success": boolean,
  "message": "string",
  "resourcesLoaded": number,
  "patientsLoaded": number,
  "bucketName": "string",
  "connectionName": "string"
}
```

### GET `/api/sample-data/availability`

Check if sample data is available in the application.

**Response:**

```json
{
  "success": boolean,
  "message": "string"
}
```

### GET `/api/sample-data/stats`

Get statistics about the sample data without loading it.

**Response:**

```json
{
  "success": boolean,
  "message": "string",
  "resourcesLoaded": number,
  "patientsLoaded": number
}
```

### GET `/api/sample-data/health`

Health check endpoint for the sample data feature.

**Response:**

```json
{
  "success": boolean,
  "message": "string"
}
```

### POST `/api/sample-data/load-with-progress`

Load sample data with real-time progress updates via Server-Sent Events (SSE).

**Request Body:**

```json
{
  "connectionName": "string",
  "bucketName": "string",
  "overwriteExisting": boolean (optional, default: false)
}
```

**Response:** Server-Sent Events stream with progress updates

**Progress Event Data:**

```json
{
  "totalFiles": number,
  "processedFiles": number,
  "currentFile": "string",
  "resourcesLoaded": number,
  "patientsLoaded": number,
  "percentComplete": number,
  "status": "STARTING|PROCESSING|COMPLETED|ERROR",
  "message": "string"
}
```

## Usage Example

1. **Check availability:**

   ```bash
   curl -X GET http://localhost:8080/api/sample-data/availability
   ```

2. **Get statistics:**

   ```bash
   curl -X GET http://localhost:8080/api/sample-data/stats
   ```

3. **Load sample data:**

   ```bash
   curl -X POST http://localhost:8080/api/sample-data/load \
     -H "Content-Type: application/json" \
     -d '{
       "connectionName": "default",
       "bucketName": "fhir-demo",
       "overwriteExisting": false
     }'
   ```

4. **Load sample data with progress (JavaScript/Frontend):**

   ```javascript
   const eventSource = new EventSource("/api/sample-data/load-with-progress", {
     method: "POST",
     headers: {
       "Content-Type": "application/json",
     },
     body: JSON.stringify({
       connectionName: "default",
       bucketName: "fhir-demo",
       overwriteExisting: false,
     }),
   });

   eventSource.addEventListener("progress", (event) => {
     const progress = JSON.parse(event.data);
     console.log(`Progress: ${progress.percentComplete}%`);
     console.log(`Status: ${progress.status}`);
     console.log(`Message: ${progress.message}`);

     // Update progress bar
     updateProgressBar(progress.percentComplete);
     updateStatusMessage(progress.message);
   });

   eventSource.addEventListener("error", (event) => {
     const error = JSON.parse(event.data);
     console.error("Error:", error.message);
     eventSource.close();
   });

   eventSource.onopen = () => {
     console.log("Progress stream opened");
   };

   eventSource.onclose = () => {
     console.log("Progress stream closed");
   };
   ```

## Sample Data

The sample data consists of synthetic patient records generated by Synthea, containing:

- Patient demographics
- Medical conditions
- Observations (vital signs, lab results)
- Medications
- Procedures
- Encounters

All data is stored in FHIR R4 format with proper resource relationships and references.

## File Structure

```
sampledata/
├── controller/
│   └── SampleDataController.java    # REST API endpoints
├── service/
│   └── SampleDataService.java       # Business logic
├── model/
│   ├── SampleDataRequest.java       # Request DTOs
│   ├── SampleDataResponse.java      # Response DTOs
│   └── SampleDataProgress.java      # Progress tracking DTOs
└── README.md                        # This file
```

## Dependencies

- Spring Boot Web
- Couchbase Java SDK
- Jackson (JSON processing)
- Spring Core (ClassPathResource)

## Error Handling

The service includes comprehensive error handling for:

- Connection failures
- Bucket access issues
- ZIP file processing errors
- JSON parsing errors
- Resource storage failures

All errors are logged and returned with appropriate HTTP status codes.
