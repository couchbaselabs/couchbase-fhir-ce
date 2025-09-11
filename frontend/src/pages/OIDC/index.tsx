import React, { useEffect, useState } from 'react';
import { Box, Typography, Paper, Chip } from '@mui/material';
import { useLocation } from 'react-router-dom';
import { createAuthClient } from 'better-auth/react';

const authBaseURL = import.meta.env.VITE_AUTH_SERVER_BASE_URL ? import.meta.env.VITE_AUTH_SERVER_BASE_URL : 'http://localhost'

const authClient = createAuthClient({
    baseURL: authBaseURL,
    basePath: "/auth"
});

const OIDC: React.FC = () => {
  const location = useLocation();
  const [params, setParams] = useState<Record<string, string>>({});

  useEffect(() => {
    // Parse search params from URL
    const searchParams = new URLSearchParams(location.search);
    const paramObj: Record<string, string> = {};
    searchParams.forEach((value, key) => {
      paramObj[key] = value;
    });
    setParams(paramObj);
  }, [location.search]);

  // Try to capture auth token from hash fragment (e.g., #access_token=...)
  useEffect(() => {
    if (location.hash) {
      const hashParams = new URLSearchParams(location.hash.replace(/^#/, ''));
      hashParams.forEach((value, key) => {
        setParams((prev) => ({ ...prev, [key]: value }));
      });
    }
  }, [location.hash]);

  return (
    <Box minHeight="100vh" display="flex" alignItems="center" justifyContent="center" bgcolor="background.default">
      <Paper elevation={3} sx={{ p: 4, minWidth: 320 }}>
        <Typography variant="h6" gutterBottom>
          OIDC Redirect Data
        </Typography>
        {Object.keys(params).length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No OIDC data found in URL.
          </Typography>
        ) : (
          <Box display="flex" flexDirection="column" gap={1}>
            {Object.entries(params).map(([key, value]) => (
              <Chip key={key} label={`${key}: ${value}`} variant="outlined" />
            ))}
          </Box>
        )}
      </Paper>
    </Box>
  );
};

export default OIDC;
