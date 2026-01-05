import React, {
  useState,
  useEffect,
  useMemo,
  useCallback,
  useRef,
} from "react";
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
  LinearProgress,
  Box,
} from "@mui/material";

interface LoadSamplesDialogProps {
  open: boolean;
  onClose: () => void;
  bucketName: string;
  connectionName: string;
  sampleType: "synthea" | "uscore" | "onc";
  onSuccess?: () => void;
}

interface SampleLoadStatus {
  totalFiles: number;
  processedFiles: number;
  currentFile: string;
  resourcesLoaded: number;
  patientsLoaded: number;
  percentComplete: number;
  status: "INITIATED" | "IN_PROGRESS" | "COMPLETED" | "ERROR" | "CANCELLED";
  message: string;
}

const LoadSamplesDialog: React.FC<LoadSamplesDialogProps> = ({
  open,
  onClose,
  bucketName,
  connectionName,
  sampleType,
  onSuccess,
}) => {
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isCompleted, setIsCompleted] = useState(false);

  // Use ref for progress to avoid re-renders of main dialog
  const progressRef = useRef<{
    updateProgress: (status: SampleLoadStatus) => void;
  }>(null);

  // Memoize sample type configuration to prevent recalculation on every render
  const sampleConfig = useMemo(() => {
    switch (sampleType) {
      case "synthea":
        return {
          title: "Load Synthea Sample Data",
          description: "Synthea-generated sample FHIR data",
          details:
            "Sample includes: 15 patients with 20 different FHIR resource types.",
          patients: 15,
          resourceTypes: 20,
          apiEndpoint: "/api/sample-data/load-with-progress",
        };
      case "uscore":
        return {
          title: "Load US Core Sample Data",
          description: "US Core-supplied sample FHIR data",
          details:
            "Sample includes: 4 patients with 28 different FHIR resource types.",
          patients: 4,
          resourceTypes: 28,
          apiEndpoint: "/api/sample-data/load-with-progress",
        };
      case "onc":
        return {
          title: "Load ONC Sample Data",
          description: "ONC sample FHIR data",
          details:
            "Sample includes: 7 patients with 12 different FHIR resource types.",
          patients: 7,
          resourceTypes: 12,
          apiEndpoint: "/api/sample-data/load-with-progress",
        };
    }
  }, [sampleType]);

  // Memoize reset function to prevent recreation on every render
  const resetState = useCallback(() => {
    setError(null);
    setInfo(null);
    setIsLoading(false);
    setIsCompleted(false);
    // Reset progress through ref without causing re-render
    if (progressRef.current) {
      progressRef.current.updateProgress({
        totalFiles: 0,
        processedFiles: 0,
        currentFile: "",
        resourcesLoaded: 0,
        patientsLoaded: 0,
        percentComplete: 0,
        status: "INITIATED",
        message: "",
      });
    }
  }, []);

  // Cleanup when dialog closes
  useEffect(() => {
    if (!open) {
      resetState();
    }
  }, [open, resetState]);

  const startSampleLoad = async () => {
    setIsLoading(true);
    setError(null);
    setInfo(`Initiating ${sampleConfig.description} loading...`);

    try {
      // First, make a POST request to start the loading process
      const startResponse = await fetch(sampleConfig.apiEndpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          connectionName: connectionName,
          bucketName: bucketName,
          overwriteExisting: false,
          sampleType: sampleType, // Include sample type for future backend support
        }),
      });

      if (!startResponse.ok) {
        throw new Error(`Failed to start loading: ${startResponse.status}`);
      }

      // Parse the SSE stream using EventSource-like parsing
      const reader = startResponse.body?.getReader();
      if (!reader) {
        throw new Error("No response body available");
      }

      const decoder = new TextDecoder();
      let buffer = "";

      let currentLoadStatus: SampleLoadStatus | null = null;

      const processSSEData = async () => {
        try {
          while (true) {
            const { done, value } = await reader.read();

            if (done) {
              // console.log("SSE stream completed");
              // If we reach here without COMPLETED status, show success
              if (
                currentLoadStatus?.status !== "COMPLETED" &&
                currentLoadStatus?.status !== "ERROR"
              ) {
                setIsLoading(false);
                setInfo(`${sampleConfig.description} loaded successfully!`);
                if (onSuccess) {
                  setTimeout(() => {
                    onSuccess();
                    setTimeout(() => handleClose(), 1500);
                  }, 1000);
                } else {
                  setTimeout(() => handleClose(), 2000);
                }
              }
              break;
            }

            // Decode chunk and add to buffer
            const chunk = decoder.decode(value, { stream: true });
            buffer += chunk;
            // console.log("Received chunk:", JSON.stringify(chunk));

            // Process complete SSE messages (split by double newlines)
            const messages = buffer.split("\n\n");
            buffer = messages.pop() || ""; // Keep incomplete message in buffer

            for (const message of messages) {
              if (message.trim() === "") continue;

              // console.log("Processing SSE message:", JSON.stringify(message));

              // Parse SSE message format
              const lines = message.split("\n");
              let eventType = "";
              let data = "";

              for (const line of lines) {
                if (line.startsWith("event:")) {
                  eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                  data = line.substring(5).trim();
                }
              }

              // console.log("Parsed event:", eventType, "data:", data);

              // Handle progress events
              if (eventType === "progress" && data) {
                try {
                  const progress = JSON.parse(data) as SampleLoadStatus;
                  // console.log("Progress update:", progress);

                  // Update both state and local variable
                  currentLoadStatus = progress;

                  // Update progress through ref to avoid main dialog re-render
                  if (progressRef.current) {
                    progressRef.current.updateProgress(progress);
                  }

                  if (progress.message) {
                    setInfo(progress.message);
                  }

                  // Handle completion
                  if (progress.status === "COMPLETED") {
                    setIsLoading(false);
                    setIsCompleted(true);
                    setInfo(
                      progress.message ||
                        `${sampleConfig.description} loaded successfully!`
                    );

                    if (onSuccess) {
                      setTimeout(() => {
                        onSuccess();
                        setTimeout(() => handleClose(), 1500);
                      }, 1000);
                    } else {
                      setTimeout(() => handleClose(), 2000);
                    }
                    return;
                  } else if (progress.status === "ERROR") {
                    setIsLoading(false);
                    setError(progress.message || "Failed to load sample data");
                    setInfo(null);
                    return;
                  }
                } catch (parseError) {
                  console.error(
                    "Failed to parse progress data:",
                    parseError,
                    "Raw data:",
                    data
                  );
                }
              } else if (eventType === "error" && data) {
                try {
                  const errorData = JSON.parse(data);
                  setIsLoading(false);
                  setError(errorData.message || "Failed to load sample data");
                  setInfo(null);
                  return;
                } catch (parseError) {
                  console.error("Failed to parse error data:", parseError);
                }
              } else {
                // console.log("Unknown or empty event:", eventType, data);
              }
            }
          }
        } catch (streamError) {
          console.error("Stream reading error:", streamError);
          setError("Connection error during sample data loading");
          setInfo(null);
          setIsLoading(false);
        } finally {
          reader.releaseLock();
        }
      };

      await processSSEData();
    } catch (err: any) {
      console.error("Failed to start sample data loading:", err);
      setError(err.message || "Failed to start sample data loading");
      setInfo(null);
      setIsLoading(false);
    }
  };

  const handleClose = useCallback(() => {
    if (!isLoading) {
      resetState();
      onClose();
    }
  }, [isLoading, resetState, onClose]);

  const canClose = !isLoading || isCompleted;

  return (
    <Dialog
      open={open}
      onClose={(_event, reason) => {
        if (reason !== "backdropClick" && canClose) {
          handleClose();
        }
      }}
      fullWidth={true}
      maxWidth={"sm"}
      disableEscapeKeyDown={!canClose}
    >
      <DialogTitle>{sampleConfig.title}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert variant="filled" severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {info && (
          <Alert variant="outlined" severity="info" sx={{ mb: 2 }}>
            Loading sample data...
          </Alert>
        )}

        <Typography gutterBottom variant="body2" sx={{ mb: 2 }}>
          This will load {sampleConfig.description} into{" "}
          <strong>{bucketName}</strong> bucket.
          <br />
          <strong>{sampleConfig.details}</strong>
        </Typography>

        {/* Progress Information */}
        <IsolatedProgressSection ref={progressRef} />

        {/* Show warning when loading is in progress */}
        {isLoading && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            Please do not close this dialog while sample data is loading.
          </Alert>
        )}
      </DialogContent>

      <DialogActions>
        <Button
          size="small"
          onClick={handleClose}
          disabled={!canClose}
          sx={{ textTransform: "none", padding: "4px 16px" }}
        >
          {isLoading ? "Please Wait..." : "Close"}
        </Button>

        {!isLoading && !isCompleted && (
          <Button
            variant="contained"
            size="small"
            onClick={startSampleLoad}
            sx={{ textTransform: "none", padding: "4px 16px" }}
          >
            Load Sample Data
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
};

// Isolated progress section that manages its own state to prevent parent re-renders
const IsolatedProgressSection = React.forwardRef<{
  updateProgress: (status: SampleLoadStatus) => void;
}>((_, ref) => {
  const [loadStatus, setLoadStatus] = useState<SampleLoadStatus | null>(null);

  // Expose update function to parent via ref
  React.useImperativeHandle(ref, () => ({
    updateProgress: (status: SampleLoadStatus) => {
      setLoadStatus(status);
    },
  }));

  // Helper function to truncate long filenames
  const getDisplayFileName = (fileName: string) => {
    if (!fileName) return "Processing...";

    // Extract just the filename from path if it's a path
    const name = fileName.split("/").pop() || fileName;

    // Truncate if too long (keep first and last parts)
    if (name.length > 50) {
      return `${name.substring(0, 20)}...${name.substring(name.length - 20)}`;
    }

    return name;
  };

  if (!loadStatus)
    return (
      <Box sx={{ mt: 2 }}>
        {/* Reserve space even when no progress to prevent height changes */}
      </Box>
    );

  return (
    <Box sx={{ mt: 2 }}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        Progress: {loadStatus.processedFiles} of {loadStatus.totalFiles} files
        processed
      </Typography>

      <LinearProgress
        variant="determinate"
        value={loadStatus.percentComplete}
        sx={{ mb: 2, height: 8, borderRadius: 4 }}
      />

      <Typography
        variant="caption"
        color="text.secondary"
        sx={{
          display: "block",
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          maxWidth: "100%",
        }}
      >
        Current file: {getDisplayFileName(loadStatus.currentFile)}
      </Typography>

      {loadStatus.resourcesLoaded > 0 && (
        <Typography
          variant="caption"
          color="text.secondary"
          display="block"
          sx={{ mt: 1 }}
        >
          Resources loaded: {loadStatus.resourcesLoaded.toLocaleString()}
        </Typography>
      )}

      {loadStatus.patientsLoaded > 0 && (
        <Typography
          variant="caption"
          color="text.secondary"
          display="block"
          sx={{ mt: 1 }}
        >
          Patients loaded: {loadStatus.patientsLoaded.toLocaleString()}
        </Typography>
      )}
    </Box>
  );
});

export default LoadSamplesDialog;
