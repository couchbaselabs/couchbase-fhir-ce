import { Box, Typography, Card, CardContent } from "@mui/material";
import DashboardConnectionFooter from "./DashboardConnectionFooter";
import DashboardFhirServer from "./DashboardFhirServer";
import ChipsArray from "../../utilities/ChipsArray";
import { useConnectionInfo } from "../Layout/DisplayContext";

export default function Dashboard() {
  const version = "1.0.0";
  const connection = useConnectionInfo();

  return (
    <Box
      sx={{
        p: 1,
        height: "100%",
        display: "flex",
        flexDirection: "column",
        width: "100%",
      }}
    >
      <Typography variant="h6" gutterBottom>
        Dashboard
      </Typography>
      <Typography
        variant="subtitle2"
        color="text.secondary"
        sx={{ lineHeight: 1.0 }}
      >
        <em>
          Welcome to Couchbase FHIR CE - Community Edition Version {version}
        </em>
      </Typography>

      <Box
        flex={1}
        display="flex"
        // flexDirection={{ xs: "column", md: "row" }}
        flexDirection="row"
        gap={2}
        mt={1}
        minHeight={0}
        width="100%"
      >
        <Box flex={1} sx={{ height: "100%", overflow: "hidden" }}>
          <Card
            sx={{ height: "100%", display: "flex", flexDirection: "column" }}
          >
            <CardContent sx={{ p: 1, flex: 1, overflow: "hidden" }}>
              {/* <Box display="flex" alignItems="center" mb=1}> */}
              <Typography
                variant="subtitle1"
                align="center"
                sx={{
                  pb: 1,
                  lineHeight: 1,
                  borderBottom: 1,
                  borderBottomColor: "divider",
                }}
              >
                Couchbase Server Details
              </Typography>
              <br />
              <Typography variant="body2" px={1} component="div">
                {connection.name} {connection.version}
                <br />
                {/* Quotas: Only show if available, with proper spacing and delimiter */}
                {(() => {
                  // For now, show basic info until we implement metrics
                  return connection.isConnected ? "Connected" : "Not Connected";
                })()}
                <br />
                <Box
                  component="span"
                  sx={{ display: "inline-flex", alignItems: "center" }}
                >
                  Services:&nbsp;
                  <ChipsArray chipData={[]} />
                </Box>
                <br />
                <br />
              </Typography>
              <DashboardConnectionFooter />
            </CardContent>
          </Card>
        </Box>
        <Box flex={1} sx={{ height: "100%", overflow: "hidden" }}>
          <Card
            sx={{ height: "100%", display: "flex", flexDirection: "column" }}
          >
            {/* CardContent must fill vertical space */}
            <CardContent
              sx={{
                p: 0,
                display: "flex",
                flexDirection: "column",
                flex: 1,
                overflow: "hidden",
              }}
            >
              {/* Fixed header */}
              <Box
                sx={{
                  px: 1,
                  py: 1,
                  borderBottom: 1,
                  borderBottomColor: "divider",
                  flexShrink: 0,
                }}
              >
                <Typography
                  variant="subtitle1"
                  align="center"
                  sx={{ lineHeight: 1 }}
                >
                  FHIR Server Details
                </Typography>
              </Box>

              {/* Scrollable content */}
              <Box
                sx={{
                  flex: 1,
                  overflowY: "auto",
                  px: 1,
                  py: 1,
                }}
              >
                <DashboardFhirServer />
              </Box>
            </CardContent>
          </Card>
        </Box>
      </Box>
    </Box>
  );
}
