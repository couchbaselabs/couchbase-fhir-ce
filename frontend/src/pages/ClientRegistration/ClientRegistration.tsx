import React, { useState, useEffect } from "react";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormLabel,
  IconButton,
  Paper,
  ToggleButton,
  ToggleButtonGroup,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
  Alert,
  Checkbox,
  FormGroup,
  Tooltip,
  InputAdornment,
  Collapse,
  Divider,
  CircularProgress,
  Snackbar,
} from "@mui/material";
import {
  Add as AddIcon,
  Delete as DeleteIcon,
  ContentCopy as CopyIcon,
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
  Info as InfoIcon,
  ExpandMore as ExpandMoreIcon,
  ExpandLess as ExpandLessIcon,
  CheckCircle as CheckCircleIcon,
  Refresh as RefreshIcon,
  Block as BlockIcon,
} from "@mui/icons-material";
import { BsKey } from "react-icons/bs";
import type { OAuthClient } from "../../services/oauthClientApi";
import {
  getAllClients,
  createClient as createClientAPI,
  revokeClient as revokeClientAPI,
  deleteClient as deleteClientAPI,
} from "../../services/oauthClientApi";
import EditorComponent from "../../components/EditorComponent";

// OAuth Client interface (will be shared with backend later)
interface OAuthClientForm {
  clientId: string;
  clientSecret?: string;
  clientName: string;
  publisherUrl?: string;
  clientType: "patient" | "provider" | "system";
  authenticationType: "public" | "confidential";
  launchType: "standalone" | "ehr-launch";
  redirectUris: string[];
  scopes: string[];
  pkceEnabled: boolean;
  pkceMethod: "S256" | "plain";
  status: "active" | "revoked";
  createdBy: string;
  createdAt: string;
  lastUsed?: string;
}

import { useLocation, useNavigate } from "react-router-dom";
import oauthClientService from "../../services/oauthClientApi";
import BulkGroupAttachModal from "../../components/BulkGroupAttachModal";
import { decodeIntent, encodeIntent } from "../../utils/intent";
import GroupAddIcon from "@mui/icons-material/GroupAdd";

// Mandatory SMART scopes factory (cannot be removed)
const getMandatoryScopes = (clientType: "patient" | "provider" | "system") => {
  // System apps don't need interactive scopes (no user, no launch, no browser)
  if (clientType === "system") {
    return []; // System apps use client_credentials - no mandatory scopes
  }

  const base = [
    {
      value: "openid",
      label: "openid",
      description: "OpenID Connect authentication",
    },
    {
      value: "fhirUser",
      label: "fhirUser",
      description: "User identity claim",
    },
    {
      value: "offline_access",
      label: "offline_access",
      description: "Refresh tokens for offline access",
    },
  ];
  const launchScope =
    clientType === "patient"
      ? {
          value: "launch/patient",
          label: "launch/patient",
          description: "Patient context at launch",
        }
      : {
          value: "launch",
          label: "launch",
          description: "App launched with user or system context",
        };
  return [launchScope, ...base];
};

// Helper function to get scope prefix based on client type
const getScopePrefix = (clientType: "patient" | "provider" | "system") => {
  switch (clientType) {
    case "patient":
      return "patient";
    case "provider":
      return "user";
    case "system":
      return "system";
    default:
      return "patient";
  }
};

// Helper function to get US Core scopes with correct prefix
const getUSCoreScopes = (clientType: "patient" | "provider" | "system") => {
  const prefix = getScopePrefix(clientType);
  const resources = [
    "Medication",
    "AllergyIntolerance",
    "CarePlan",
    "CareTeam",
    "Condition",
    "Coverage",
    "Device",
    "DiagnosticReport",
    "DocumentReference",
    "Encounter",
    "Goal",
    "Immunization",
    "Location",
    "MedicationDispense",
    "MedicationRequest",
    "Observation",
    "Organization",
    "Patient",
    "Practitioner",
    "PractitionerRole",
    "Procedure",
    "Provenance",
    "RelatedPerson",
    "ServiceRequest",
    "Specimen",
  ];
  return resources.map((r) => `${prefix}/${r}.rs`);
};

// Legacy: kept for backwards compatibility
const US_CORE_RESOURCE_SCOPES = getUSCoreScopes("patient");

// Additional optional scopes
const OPTIONAL_SCOPES = [
  {
    value: "profile",
    label: "profile",
    description: "User profile information",
  },
  {
    value: "user/*.read",
    label: "user/*.read",
    description: "Read user-accessible data",
  },
  {
    value: "user/*.write",
    label: "user/*.write",
    description: "Write user-accessible data",
  },
  {
    value: "system/*.read",
    label: "system/*.read",
    description: "Read all system data (backend)",
  },
  {
    value: "system/*.write",
    label: "system/*.write",
    description: "Write all system data (backend)",
  },
];

const ClientRegistration: React.FC = () => {
  // State
  const [clients, setClients] = useState<OAuthClient[]>([]);
  const [loading, setLoading] = useState(true);

  // Dialog states
  const [registerDialogOpen, setRegisterDialogOpen] = useState(false);
  const [createdClientDialogOpen, setCreatedClientDialogOpen] = useState(false);
  const [newlyCreatedClient, setNewlyCreatedClient] =
    useState<OAuthClient | null>(null);
  const [showClientSecret, setShowClientSecret] = useState(false);

  // Form state
  const [formData, setFormData] = useState({
    clientName: "",
    publisherUrl: "",
    clientType: "patient" as "patient" | "provider" | "system",
    authenticationType: "public" as "public" | "confidential",
    launchType: "standalone" as "standalone" | "ehr-launch",
    redirectUris: [] as string[],
    scopes: [
      "openid",
      "fhirUser",
      "launch/patient",
      "offline_access",
    ] as string[],
    pkceEnabled: true,
    pkceMethod: "S256" as "S256" | "plain",
  });

  const [redirectUriInput, setRedirectUriInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [copySnackbar, setCopySnackbar] = useState(false);
  const [revokeDialogOpen, setRevokeDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedClient, setSelectedClient] = useState<OAuthClient | null>(
    null
  );
  // Bulk group attach modal state
  const [attachModalOpen, setAttachModalOpen] = useState(false);
  const [attachClientId, setAttachClientId] = useState<string | null>(null);
  const [initialAttachGroups, setInitialAttachGroups] = useState<any[] | null>(
    null
  );
  const [initialSelectedGroupId, setInitialSelectedGroupId] = useState<
    string | undefined
  >(undefined);

  // Client details dialog state
  const [detailsDialogOpen, setDetailsDialogOpen] = useState(false);
  const [selectedClientForDetails, setSelectedClientForDetails] =
    useState<OAuthClient | null>(null);
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    // handle intent_response when redirected back from BulkGroups
    const q = new URLSearchParams(location.search);
    const enc = q.get("intent_response");
    if (enc) {
      const resp = decodeIntent(enc);
      if (
        resp &&
        resp.action === "bulk_group_created" &&
        resp.clientId &&
        resp.group
      ) {
        // Instead of auto-attaching, open the attach modal and preselect the created group
        setAttachClientId(resp.clientId);
        setInitialAttachGroups([resp.group]);
        setInitialSelectedGroupId(resp.group.id);
        setAttachModalOpen(true);

        // remove the intent_response from URL so it doesn't trigger again
        const params = new URLSearchParams(location.search);
        params.delete("intent_response");
        navigate({ pathname: location.pathname, search: params.toString() }, {
          replace: true,
        } as any);
      }
    }
  }, []);
  // New scopes UI state
  const [scopeMode, setScopeMode] = useState<"custom" | "all-read" | "us-core">(
    "custom"
  );
  const [scopesText, setScopesText] = useState<string>("");

  // Load clients on mount
  useEffect(() => {
    loadClients();
  }, []);

  const loadClients = async () => {
    try {
      setLoading(true);
      setError(null);
      const fetchedClients = await getAllClients();
      setClients(fetchedClients);
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to load OAuth clients");
    } finally {
      setLoading(false);
    }
  };

  const handleOpenRegisterDialog = () => {
    resetForm();
    setRegisterDialogOpen(true);
  };

  const resetForm = () => {
    setFormData({
      clientName: "",
      publisherUrl: "",
      clientType: "patient",
      authenticationType: "public",
      launchType: "standalone",
      redirectUris: [],
      scopes: getMandatoryScopes("patient").map((s) => s.value),
      pkceEnabled: true,
      pkceMethod: "S256",
    });
    setRedirectUriInput("");
    setError(null);
    // Reset new scopes UI
    setScopeMode("custom");
    setScopesText("");
  };

  const handleAddRedirectUri = () => {
    if (!redirectUriInput.trim()) {
      setError("Redirect URI cannot be empty");
      return;
    }
    if (
      !redirectUriInput.startsWith("http://") &&
      !redirectUriInput.startsWith("https://")
    ) {
      setError("Redirect URI must start with http:// or https://");
      return;
    }
    if (formData.redirectUris.includes(redirectUriInput)) {
      setError("Redirect URI already added");
      return;
    }
    setFormData({
      ...formData,
      redirectUris: [...formData.redirectUris, redirectUriInput],
    });
    setRedirectUriInput("");
    setError(null);
  };

  const handleRemoveRedirectUri = (uri: string) => {
    setFormData({
      ...formData,
      redirectUris: formData.redirectUris.filter((u) => u !== uri),
    });
  };

  // Scopes preset handler
  const applyScopePreset = (mode: "custom" | "all-read" | "us-core") => {
    setScopeMode(mode);
    if (mode === "custom") {
      setScopesText("");
    } else if (mode === "all-read") {
      const prefix = getScopePrefix(formData.clientType);
      setScopesText(`${prefix}/*.rs`);
    } else if (mode === "us-core") {
      const usCoreScopes = getUSCoreScopes(formData.clientType);
      setScopesText(usCoreScopes.join(" "));
    }
  };

  // Parse scopesText into array (space-delimited)
  const parsedOptionalScopes = (text: string) => {
    return text
      .split(/\s+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  };

  const handleRegisterClient = async () => {
    // Validation
    if (!formData.clientName.trim()) {
      setError("App name is required");
      return;
    }

    // System apps: require confidential authentication
    if (
      formData.clientType === "system" &&
      formData.authenticationType !== "confidential"
    ) {
      setError(
        "System apps must use confidential authentication (client_credentials flow requires a secret)"
      );
      return;
    }

    // Patient/Provider apps: require redirect URIs
    if (
      formData.clientType !== "system" &&
      formData.redirectUris.length === 0
    ) {
      setError("At least one redirect URI is required for interactive apps");
      return;
    }

    // Build final scopes: mandatory + parsed from textarea
    const mandatoryScopes = getMandatoryScopes(formData.clientType).map(
      (s) => s.value
    );
    const optionalScopes = parsedOptionalScopes(scopesText);
    const finalScopes = Array.from(
      new Set([...mandatoryScopes, ...optionalScopes])
    );

    if (finalScopes.length === 0) {
      setError("At least one scope is required");
      return;
    }

    try {
      setError(null);

      // Call API to create client
      const newClient = await createClientAPI({
        clientName: formData.clientName,
        publisherUrl: formData.publisherUrl || undefined,
        clientType: formData.clientType,
        authenticationType: formData.authenticationType,
        launchType: formData.launchType,
        redirectUris: formData.redirectUris,
        scopes: finalScopes,
        pkceEnabled: formData.pkceEnabled,
        pkceMethod: formData.pkceMethod,
      });

      // Show success dialog with credentials
      setNewlyCreatedClient(newClient);
      setRegisterDialogOpen(false);
      setCreatedClientDialogOpen(true);
      setSuccess("Client registered successfully");

      // Reload clients list
      loadClients();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to create OAuth client");
    }
  };

  const handleCopyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopySnackbar(true);
  };

  const handleRevokeClient = async (clientId: string) => {
    try {
      setError(null);
      await revokeClientAPI(clientId);
      setSuccess("Client revoked successfully");
      loadClients();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to revoke OAuth client");
    }
  };

  const handleDeleteClient = async (clientId: string) => {
    try {
      setError(null);
      await deleteClientAPI(clientId);
      setSuccess("Client permanently deleted");
      loadClients();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to delete OAuth client");
    }
  };

  return (
    <Box sx={{ p: 2, width: "100%" }}>
      {/* Header */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 2,
          gap: 2,
        }}
      >
        <Box>
          <Typography variant="h6">OAuth Client Registration</Typography>
          <Typography variant="caption" color="text.secondary">
            Register SMART on FHIR applications for OAuth 2.0 authentication
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={handleOpenRegisterDialog}
        >
          Register New App
        </Button>
      </Box>
      {/* Registered Clients Table */}
      <Card sx={{ width: "100%", minHeight: 200 }}>
        <CardContent>
          {loading ? (
            <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
              <CircularProgress />
            </Box>
          ) : clients.length === 0 ? (
            <Box sx={{ textAlign: "center", py: 4 }}>
              <BsKey size={48} style={{ opacity: 0.3 }} />
              <Typography variant="body1" color="text.secondary" sx={{ mt: 2 }}>
                No registered clients yet
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Click "Register New App" to create your first OAuth client
              </Typography>
            </Box>
          ) : (
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>App Name</TableCell>
                    <TableCell>Client ID</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>Launch</TableCell>
                    <TableCell>Auth</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Bulk Group</TableCell>
                    <TableCell>Created</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {clients.map((client) => (
                    <TableRow
                      key={client.clientId}
                      hover
                      onClick={() => {
                        setSelectedClientForDetails(client);
                        setDetailsDialogOpen(true);
                      }}
                      sx={{ cursor: "pointer" }}
                    >
                      <TableCell>
                        <Typography variant="body2" fontWeight="medium">
                          {client.clientName}
                        </Typography>
                        {client.publisherUrl && (
                          <Typography variant="caption" color="text.secondary">
                            {client.publisherUrl}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography
                          variant="body2"
                          sx={{ fontFamily: "monospace", fontSize: "0.8rem" }}
                        >
                          {client.clientId}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip label={client.clientType} size="small" />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={client.launchType}
                          size="small"
                          variant="outlined"
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={client.authenticationType}
                          size="small"
                          color={
                            client.authenticationType === "confidential"
                              ? "warning"
                              : "default"
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={client.status}
                          size="small"
                          color={
                            client.status === "active" ? "success" : "default"
                          }
                        />
                      </TableCell>
                      <TableCell>
                        {client.bulkGroupId ? (
                          <Chip
                            label={client.bulkGroupId}
                            size="small"
                            clickable
                            color="primary"
                            onClick={() => {
                              const intent = {
                                intent_type: "highlight_bulk_group",
                                groupId: client.bulkGroupId,
                                flashCount: 20,
                                sourcePath: "/clients",
                              };
                              const enc = encodeIntent(intent);
                              navigate(`/fhir-groups?intent=${enc}`);
                            }}
                          />
                        ) : (
                          <Typography variant="caption" color="text.secondary">
                            —
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption">
                          {client.createdAt
                            ? new Date(client.createdAt).toLocaleDateString()
                            : "-"}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Tooltip
                          title={
                            client.status === "revoked"
                              ? "Already revoked"
                              : "Revoke client"
                          }
                        >
                          <span>
                            <IconButton
                              size="small"
                              onClick={(e) => {
                                e.stopPropagation();
                                setSelectedClient(client);
                                setRevokeDialogOpen(true);
                              }}
                              disabled={client.status === "revoked"}
                              color="warning"
                            >
                              <BlockIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        {client.clientType === "system" && (
                          <Tooltip title="Attach bulk group (System apps only)">
                            <IconButton
                              size="small"
                              onClick={(e) => {
                                e.stopPropagation();
                                setAttachClientId(client.clientId);
                                setAttachModalOpen(true);
                              }}
                            >
                              <GroupAddIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        )}
                        <Tooltip title="Permanently delete">
                          <IconButton
                            size="small"
                            onClick={(e) => {
                              e.stopPropagation();
                              setSelectedClient(client);
                              setDeleteDialogOpen(true);
                            }}
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Register Client Dialog */}
      <Dialog
        open={registerDialogOpen}
        onClose={() => setRegisterDialogOpen(false)}
        maxWidth="md"
        fullWidth
        slotProps={{
          paper: {
            sx: { maxHeight: "90vh", backgroundColor: "default.background" },
          },
        }}
      >
        <DialogTitle sx={{ pb: 1 }}>Register New OAuth Client</DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
            {/* App Name */}
            <TextField
              label="App Name"
              value={formData.clientName}
              onChange={(e) =>
                setFormData({ ...formData, clientName: e.target.value })
              }
              fullWidth
              required
              helperText="User-friendly name for your application"
            />

            {/* Publisher URL */}
            <TextField
              label="App Publisher URL"
              value={formData.publisherUrl}
              onChange={(e) =>
                setFormData({ ...formData, publisherUrl: e.target.value })
              }
              fullWidth
              helperText="Optional: Your organization's website"
            />

            {/* Client Type */}
            <FormControl component="fieldset">
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <FormLabel component="legend" sx={{ mb: 0 }}>
                  Client Type
                </FormLabel>
                <ToggleButtonGroup
                  exclusive
                  color="primary"
                  size="small"
                  value={formData.clientType}
                  onChange={(_, newValue) => {
                    if (newValue) {
                      // Force confidential auth for system apps
                      const updates: any = { clientType: newValue };
                      if (newValue === "system") {
                        updates.authenticationType = "confidential";
                      }
                      setFormData({ ...formData, ...updates });
                    }
                  }}
                >
                  <ToggleButton value="patient" sx={{ textTransform: "none" }}>
                    Patient App
                  </ToggleButton>
                  <ToggleButton value="provider" sx={{ textTransform: "none" }}>
                    Provider App
                  </ToggleButton>
                  <ToggleButton value="system" sx={{ textTransform: "none" }}>
                    System/Backend
                  </ToggleButton>
                </ToggleButtonGroup>
              </Box>
              <Typography variant="caption" color="text.secondary">
                Patient: Standalone patient-facing apps | Provider:
                Clinician-facing apps | System: Backend services
              </Typography>
            </FormControl>

            {/* Launch Type - Hide for system apps */}
            {formData.clientType !== "system" && (
              <FormControl component="fieldset">
                <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                  <FormLabel component="legend" sx={{ mb: 0 }}>
                    Launch Type
                  </FormLabel>
                  <ToggleButtonGroup
                    exclusive
                    size="small"
                    color="primary"
                    value={formData.launchType}
                    onChange={(_, newValue) => {
                      if (newValue) {
                        setFormData({ ...formData, launchType: newValue });
                      }
                    }}
                  >
                    <ToggleButton
                      value="standalone"
                      sx={{ textTransform: "none" }}
                    >
                      Standalone Launch
                    </ToggleButton>
                    <ToggleButton
                      value="ehr-launch"
                      disabled
                      sx={{ textTransform: "none" }}
                    >
                      EHR Launch
                    </ToggleButton>
                  </ToggleButtonGroup>
                </Box>
                <Typography variant="caption" color="text.secondary">
                  Standalone: App launches independently | EHR Launch: Launched
                  from within EHR (coming soon)
                </Typography>
              </FormControl>
            )}

            {/* Authentication Type */}
            <FormControl component="fieldset">
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <FormLabel component="legend" sx={{ mb: 0 }}>
                  Authentication
                </FormLabel>
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  color="primary"
                  value={formData.authenticationType}
                  onChange={(_, newValue) => {
                    if (newValue) {
                      setFormData({
                        ...formData,
                        authenticationType: newValue,
                      });
                    }
                  }}
                >
                  <ToggleButton
                    value="public"
                    sx={{ textTransform: "none" }}
                    disabled={formData.clientType === "system"}
                  >
                    Public Client
                  </ToggleButton>
                  <ToggleButton
                    value="confidential"
                    sx={{ textTransform: "none" }}
                  >
                    Confidential Client
                  </ToggleButton>
                </ToggleButtonGroup>
              </Box>
              <Typography variant="caption" color="text.secondary">
                {formData.clientType === "system"
                  ? "System apps must be confidential (client_credentials requires a secret)"
                  : "Public: No client secret (mobile/browser apps) | Confidential: Uses client secret (server apps)"}
              </Typography>
            </FormControl>

            {/* PKCE Settings */}
            <Box>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={formData.pkceEnabled}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        pkceEnabled: e.target.checked,
                      })
                    }
                  />
                }
                label="Enable PKCE (Proof Key for Code Exchange)"
              />
              {formData.pkceEnabled && (
                <FormControl component="fieldset" sx={{ ml: 4, mt: 0.5 }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                    <FormLabel component="legend" sx={{ mb: 0 }}>
                      PKCE Challenge Method
                    </FormLabel>
                    <ToggleButtonGroup
                      exclusive
                      size="small"
                      color="primary"
                      value={formData.pkceMethod}
                      onChange={(_, newValue) => {
                        if (newValue) {
                          setFormData({ ...formData, pkceMethod: newValue });
                        }
                      }}
                    >
                      <ToggleButton value="S256" sx={{ textTransform: "none" }}>
                        S256 (SHA-256)
                      </ToggleButton>
                      <ToggleButton
                        value="plain"
                        sx={{ textTransform: "none" }}
                      >
                        Plain
                      </ToggleButton>
                    </ToggleButtonGroup>
                  </Box>
                </FormControl>
              )}
            </Box>

            {/* Redirect URIs - Optional for system apps */}
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Redirect URIs {formData.clientType !== "system" && "*"}
                <Tooltip
                  title={
                    formData.clientType === "system"
                      ? "Not required for system apps (no browser redirect)"
                      : "URLs where the authorization server will redirect after authentication"
                  }
                >
                  <IconButton size="small">
                    <InfoIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Typography>

              {formData.clientType === "system" ? (
                <Alert severity="info" sx={{ mb: 2 }}>
                  System apps use client_credentials flow (no user interaction,
                  no redirect)
                </Alert>
              ) : (
                <>
                  <Box sx={{ display: "flex", gap: 1, mb: 1 }}>
                    <TextField
                      size="small"
                      fullWidth
                      value={redirectUriInput}
                      onChange={(e) => setRedirectUriInput(e.target.value)}
                      placeholder="https://example.com/callback"
                      onKeyPress={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          handleAddRedirectUri();
                        }
                      }}
                    />
                    <Button variant="outlined" onClick={handleAddRedirectUri}>
                      Add
                    </Button>
                  </Box>
                  {formData.redirectUris.length > 0 && (
                    <Paper variant="outlined" sx={{ p: 1 }}>
                      {formData.redirectUris.map((uri) => (
                        <Box
                          key={uri}
                          sx={{
                            display: "flex",
                            justifyContent: "space-between",
                            alignItems: "center",
                            p: 1,
                          }}
                        >
                          <Typography
                            variant="body2"
                            sx={{ fontFamily: "monospace" }}
                          >
                            {uri}
                          </Typography>
                          <IconButton
                            size="small"
                            onClick={() => handleRemoveRedirectUri(uri)}
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Box>
                      ))}
                    </Paper>
                  )}
                </>
              )}
            </Box>

            {/* Scopes */}
            <Box>
              <Typography
                variant="subtitle2"
                gutterBottom
                sx={{ fontWeight: 600 }}
              >
                Allowed Scopes *
              </Typography>

              {/* Mandatory Scopes - Chips (non-removable) */}
              <Box sx={{ mb: 2 }}>
                {formData.clientType === "system" ? (
                  <Alert severity="info">
                    System apps don't require launch/openid/fhirUser scopes (no
                    user interaction)
                  </Alert>
                ) : (
                  <>
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      gutterBottom
                      display="block"
                    >
                      Required Scopes (cannot be removed):
                    </Typography>
                    <Box
                      sx={{
                        display: "flex",
                        flexWrap: "wrap",
                        gap: 0.5,
                        mt: 1,
                      }}
                    >
                      {getMandatoryScopes(formData.clientType).map((scope) => (
                        <Chip
                          key={scope.value}
                          label={scope.value}
                          size="small"
                          color="primary"
                          icon={<CheckCircleIcon />}
                        />
                      ))}
                    </Box>
                  </>
                )}
              </Box>

              <Divider sx={{ my: 2 }} />

              {/* New scopes preset toggle + textarea */}
              <Box>
                <Box
                  sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1 }}
                >
                  <FormLabel component="legend" sx={{ mb: 0 }}>
                    Add
                  </FormLabel>
                  <ToggleButtonGroup
                    exclusive
                    size="small"
                    color="primary"
                    value={scopeMode}
                    onChange={(_, newValue) => {
                      if (newValue) {
                        applyScopePreset(newValue);
                      }
                    }}
                  >
                    <ToggleButton value="custom" sx={{ textTransform: "none" }}>
                      Custom
                    </ToggleButton>
                    <ToggleButton
                      value="all-read"
                      sx={{ textTransform: "none" }}
                    >
                      All Read
                    </ToggleButton>
                    <ToggleButton
                      value="us-core"
                      sx={{ textTransform: "none" }}
                    >
                      US-Core
                    </ToggleButton>
                  </ToggleButtonGroup>
                </Box>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                  sx={{ mb: 0.5 }}
                >
                  Enter space-delimited list of scopes
                </Typography>
                <TextField
                  multiline
                  minRows={3}
                  fullWidth
                  placeholder={`e.g. ${getScopePrefix(
                    formData.clientType
                  )}/*.rs (SMART v2: rs=read+search)`}
                  value={scopesText}
                  onChange={(e) => setScopesText(e.target.value)}
                />
              </Box>
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRegisterDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            onClick={handleRegisterClient}
            disabled={
              formData.redirectUris.length === 0 || !formData.clientName.trim()
            }
          >
            Register Client
          </Button>
        </DialogActions>
      </Dialog>

      {/* Client Created Success Dialog */}
      <Dialog
        open={createdClientDialogOpen}
        onClose={() => setCreatedClientDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <BsKey size={24} />
            Client Registered Successfully
          </Box>
        </DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 3 }}>
            <Typography variant="body2" fontWeight="medium">
              Save these credentials now!
            </Typography>
            <Typography variant="caption">
              The client secret will not be shown again for security reasons.
            </Typography>
          </Alert>

          {newlyCreatedClient && (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
              {/* Client ID */}
              <Box>
                <Typography
                  variant="subtitle2"
                  color="text.secondary"
                  gutterBottom
                >
                  Client ID
                </Typography>
                <TextField
                  fullWidth
                  value={newlyCreatedClient.clientId}
                  InputProps={{
                    readOnly: true,
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton
                          onClick={() =>
                            handleCopyToClipboard(newlyCreatedClient.clientId)
                          }
                        >
                          <CopyIcon />
                        </IconButton>
                      </InputAdornment>
                    ),
                  }}
                />
              </Box>

              {/* Client Secret (only for confidential clients) */}
              {newlyCreatedClient.clientSecret && (
                <Box>
                  <Typography
                    variant="subtitle2"
                    color="text.secondary"
                    gutterBottom
                  >
                    Client Secret
                  </Typography>
                  <TextField
                    fullWidth
                    type={showClientSecret ? "text" : "password"}
                    value={newlyCreatedClient.clientSecret}
                    InputProps={{
                      readOnly: true,
                      endAdornment: (
                        <InputAdornment position="end">
                          <IconButton
                            onClick={() =>
                              setShowClientSecret(!showClientSecret)
                            }
                          >
                            {showClientSecret ? (
                              <VisibilityOffIcon />
                            ) : (
                              <VisibilityIcon />
                            )}
                          </IconButton>
                          <IconButton
                            onClick={() =>
                              handleCopyToClipboard(
                                newlyCreatedClient.clientSecret!
                              )
                            }
                          >
                            <CopyIcon />
                          </IconButton>
                        </InputAdornment>
                      ),
                    }}
                  />
                </Box>
              )}

              {/* App Info */}
              <Paper
                variant="outlined"
                sx={{ p: 2, bgcolor: "background.default" }}
              >
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  App Name: <strong>{newlyCreatedClient.clientName}</strong>
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  Type: <strong>{newlyCreatedClient.clientType}</strong>
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  Authentication:{" "}
                  <strong>{newlyCreatedClient.authenticationType}</strong>
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  PKCE:{" "}
                  <strong>
                    {newlyCreatedClient.pkceEnabled ? "Enabled" : "Disabled"}
                  </strong>
                  {newlyCreatedClient.pkceEnabled &&
                    ` (${newlyCreatedClient.pkceMethod})`}
                </Typography>
              </Paper>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button
            variant="contained"
            onClick={() => setCreatedClientDialogOpen(false)}
          >
            Done
          </Button>
        </DialogActions>
      </Dialog>

      {/* Revoke Client Dialog */}
      <Dialog
        open={revokeDialogOpen}
        onClose={() => setRevokeDialogOpen(false)}
      >
        <DialogTitle>Revoke Client?</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to revoke{" "}
            <strong>{selectedClient?.clientName}</strong>? This will immediately
            disable the client but keep it in the database. Revoked clients
            cannot be reactivated.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRevokeDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={async () => {
              if (selectedClient) {
                await handleRevokeClient(selectedClient.clientId);
                setRevokeDialogOpen(false);
                setSelectedClient(null);
              }
            }}
            color="warning"
            variant="contained"
          >
            Revoke
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete Client Dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
      >
        <DialogTitle>Permanently Delete Client</DialogTitle>
        <DialogContent>
          <Typography color="error" sx={{ mb: 1 }}>
            ⚠️ <strong>WARNING: This action cannot be undone!</strong>
          </Typography>
          <Typography>
            Are you sure you want to permanently delete{" "}
            <strong>{selectedClient?.clientName}</strong>? This will remove all
            client data from the system.
          </Typography>
          <Typography sx={{ mt: 2 }} color="text.secondary">
            Consider using "Revoke" instead to keep the client record.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={async () => {
              if (selectedClient) {
                await handleDeleteClient(selectedClient.clientId);
                setDeleteDialogOpen(false);
                setSelectedClient(null);
              }
            }}
            variant="contained"
          >
            Permanently Delete
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={copySnackbar}
        autoHideDuration={2000}
        onClose={() => setCopySnackbar(false)}
        message="Copied to clipboard!"
      />

      {/* Client Details Dialog */}
      <Dialog
        open={detailsDialogOpen}
        onClose={() => {
          setDetailsDialogOpen(false);
          setSelectedClientForDetails(null);
        }}
        maxWidth="md"
        fullWidth
        slotProps={{
          paper: {
            sx: { maxHeight: "90vh" },
          },
        }}
      >
        <DialogTitle>
          <Box
            sx={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
            }}
          >
            <Typography variant="h6">Client Details</Typography>
            <IconButton
              size="small"
              onClick={() => {
                if (selectedClientForDetails) {
                  handleCopyToClipboard(
                    JSON.stringify(selectedClientForDetails, null, 2)
                  );
                }
              }}
              sx={{ ml: 2 }}
            >
              <CopyIcon fontSize="small" />
            </IconButton>
          </Box>
          {selectedClientForDetails && (
            <Typography variant="caption" color="text.secondary">
              {selectedClientForDetails.clientName}
            </Typography>
          )}
        </DialogTitle>
        <DialogContent sx={{ p: 0, height: "500px" }}>
          {selectedClientForDetails && (
            <EditorComponent
              value={JSON.stringify(selectedClientForDetails, null, 2)}
              language="json"
              height="500px"
            />
          )}
        </DialogContent>
        <DialogActions>
          <Button
            onClick={() => {
              setDetailsDialogOpen(false);
              setSelectedClientForDetails(null);
            }}
          >
            Close
          </Button>
        </DialogActions>
      </Dialog>

      <BulkGroupAttachModal
        open={attachModalOpen}
        onClose={() => {
          setAttachModalOpen(false);
          setInitialAttachGroups(null);
          setInitialSelectedGroupId(undefined);
          setAttachClientId(null);
        }}
        clientId={attachClientId || ""}
        initialGroups={initialAttachGroups || undefined}
        initialSelectedGroupId={initialSelectedGroupId}
        onAttached={async (updated) => {
          await loadClients();
          setAttachModalOpen(false);
          setInitialAttachGroups(null);
          setInitialSelectedGroupId(undefined);
          setAttachClientId(null);
        }}
      />
    </Box>
  );
};

export default ClientRegistration;
