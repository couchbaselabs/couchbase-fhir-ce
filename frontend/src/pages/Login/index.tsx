

import React, { useState } from 'react';
import { Box, Typography, TextField, Button, Alert, Paper } from '@mui/material';
import { formStyles } from '../../styles/styles';
import { useThemeContext } from '../../contexts/ThemeContext';
import { useNavigate } from 'react-router-dom';
import { createAuthClient } from 'better-auth/react';
import CouchbaseLogo from '../../assets/icons/couchbase.png';
import {useAuthStore} from '../../store/authStore';

const authBaseURL = import.meta.env.VITE_AUTH_SERVER_BASE_URL ? import.meta.env.VITE_AUTH_SERVER_BASE_URL : 'http://localhost'

const authClient = createAuthClient({
    baseURL: authBaseURL,
    basePath: "/auth"
});

const Login: React.FC = () => {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState<string | null>(null);
    const { themeMode } = useThemeContext();
    const navigate = useNavigate();
    const authStore = useAuthStore();

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        try {
            const {data, error} = await authClient.signIn.email({
                email,
                password,
            });

            if(data) {

                const res = await fetch("http://localhost:5000/auth/token", {
                    credentials: 'include'
                })

                const data_ = await res.json();

                console.log(data_)

                authStore.setAuthInfo(true, data_.token, '')
                console.log("Success Login Response", data);
                setTimeout(() => {
                    navigate('/dashboard');
                }, 1000 * 60)
            }
            else {
                setError(error.message ?? "Login Failed");
            }
        } catch (err: any) {
            setError(err?.message || 'Login failed');
        }
    };

    return (
        <Box
            sx={{
                minHeight: '100vh',
                width: '100vw',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                bgcolor: themeMode === 'dark' ? 'background.default' : 'grey.100',
            }}
        >
            <Paper
                elevation={4}
                sx={{
                    ...formStyles,
                    maxWidth: 400,
                    width: '100%',
                    mx: 'auto',
                    p: 4,
                    boxShadow: 4,
                    borderRadius: 3,
                    bgcolor: themeMode === 'dark' ? 'background.paper' : 'white',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                }}
            >
                <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', mb: 2 }}>
                    <img
                        src={CouchbaseLogo}
                        alt="Couchbase"
                        height={48}
                        style={{ marginBottom: 8, filter: themeMode === 'dark' ? 'brightness(1.0)' : 'none' }}
                    />
                    <Typography variant="h6" sx={{ fontWeight: 'bold', lineHeight: 1 }} align="center">
                        Couchbase
                    </Typography>
                    <Typography variant="h5" sx={{ fontWeight: 'bold', lineHeight: 1 }} align="center" color="primary">
                        FHIR CE
                    </Typography>
                </Box>
                <Typography variant="subtitle1" gutterBottom align="center" sx={{ mb: 2 }}>
                    Login
                </Typography>
                <form onSubmit={handleSubmit} style={{ width: '100%' }}>
                    <TextField
                        label="Email"
                        type="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        required
                        fullWidth
                        sx={{ mb: 2 }}
                    />
                    <TextField
                        label="Password"
                        type="password"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        required
                        fullWidth
                        sx={{ mb: 2 }}
                    />
                    <Button type="submit" variant="contained" color="primary" fullWidth>
                        Sign In
                    </Button>
                    {error && (
                        <Alert severity="error" sx={{ mt: 2 }}>
                            {error}
                        </Alert>
                    )}
                </form>
            </Paper>
        </Box>
    );
};

export default Login;


