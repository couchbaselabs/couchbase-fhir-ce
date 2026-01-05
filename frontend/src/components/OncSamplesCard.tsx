import React from "react";
import {
  Card,
  CardContent,
  Typography,
  Box,
  CardActionArea,
  CardHeader,
} from "@mui/material";

interface OncSamplesCardProps {
  onClick: () => void;
  disabled?: boolean;
}

const OncSamplesCard: React.FC<OncSamplesCardProps> = ({
  onClick,
  disabled = false,
}) => {
  return (
    <Card
      elevation={4} // Added elevation for a subtle shadow effect
      onFocus={(e) => e.target.blur()}
      sx={{
        minHeight: 300,
        maxHeight: 300,
        maxWidth: 300,
        overflow: "auto",
        opacity: disabled ? 0.6 : 1,
        cursor: disabled ? "not-allowed" : "pointer",
        "&:focus": {
          outline: "none",
        },
      }}
    >
      <CardActionArea
        onClick={!disabled ? onClick : undefined}
        disabled={disabled}
      >
        <CardHeader
          sx={{ px: 1, py: 0 }}
          title={
            <Typography variant="h6" sx={{ color: "primary.main" }}>
              ONC Sample Data
            </Typography>
          }
          subheader={
            <Typography variant="body2">
              Sample FHIR data for ONC compliance testing
            </Typography>
          }
        />
        <CardContent sx={{ px: 2, py: 2 }}>
          <Typography variant="body2" component="div">
            This card provides a quick way to load ONC sample FHIR data into
            your bucket.
            <br />
            <br />
            <strong>Sample includes:</strong> 7 patients with 12 different FHIR
            resource types.
          </Typography>

          <Box display="flex" gap={2} alignItems="center" mt={2}>
            <Box display="flex" alignItems="center" gap={1}>
              <Typography variant="caption" color="text.secondary">
                Patients:
              </Typography>
              <Typography variant="body2" fontWeight="medium">
                7
              </Typography>
            </Box>
            <Box display="flex" alignItems="center" gap={1}>
              <Typography variant="caption" color="text.secondary">
                Resource Types:
              </Typography>
              <Typography variant="body2" fontWeight="medium">
                12
              </Typography>
            </Box>
          </Box>

          <Typography
            variant="caption"
            color="text.secondary"
            display="block"
            mt={1}
          >
            Perfect for demonstrations, development, and testing FHIR workflows.
          </Typography>
        </CardContent>
      </CardActionArea>
    </Card>
  );
};

export default OncSamplesCard;
