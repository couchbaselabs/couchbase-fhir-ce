import { Route, BrowserRouter as Router, Routes } from "react-router-dom";
import {
  ThemeProvider as MuiThemeProvider,
  createTheme,
} from "@mui/material/styles";
import CssBaseline from "@mui/material/CssBaseline";
import { ConnectionProvider } from "./pages/Layout/ConnectionProvider";
import MainLayout from "./pages/Layout/MainLayout";
import AppRoutes from "./routes/AppRoutes";
import { ThemeProvider, useThemeContext } from "./contexts/ThemeContext";
import "@fontsource/roboto/300.css";
import "@fontsource/roboto/400.css";
import "@fontsource/roboto/500.css";
import "@fontsource/roboto/700.css";
import Login from "./pages/Login";
import OIDC from "./pages/OIDC";

function AppContent() {
  const { themeMode } = useThemeContext();

  // Create theme with consistent typography and form component overrides
  const theme = createTheme({
    palette: {
      mode: themeMode,
    },
    typography: {
      // Set consistent font sizes
      fontSize: 14, // Base font size (0.875rem)
      body1: {
        fontSize: "0.875rem", // 14px
      },
      body2: {
        fontSize: "0.875rem", // 14px
      },
    },
    components: {
      // Override MUI components to use consistent font sizes
      MuiTextField: {
        defaultProps: {
          variant: "outlined",
          size: "small",
        },
        styleOverrides: {
          root: {
            "& .MuiInputBase-root": {
              fontSize: "0.875rem",
            },
            "& .MuiInputLabel-root": {
              fontSize: "0.875rem",
            },
            "& .MuiFormHelperText-root": {
              fontSize: "0.75rem",
            },
            "& .MuiInputBase-root-MuiOutlinedInput-root": {
              fontSize: "0.875rem",
            },
          },
        },
      },
      MuiSelect: {
        defaultProps: {
          variant: "outlined",
          size: "small",
        },
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
          },
          select: {
            fontSize: "0.875rem",
          },
        },
      },
      MuiMenuItem: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
          },
        },
      },
      MuiInputLabel: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
          },
        },
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
          },
          input: {
            fontSize: "0.875rem",
          },
        },
      },
      MuiFormControl: {
        defaultProps: {
          variant: "outlined",
          size: "small",
        },
      },
      MuiButton: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
            textTransform: "none", // Disable uppercase transformation
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
          },
        },
      },
      MuiTab: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
            textTransform: "none",
            margin: 0,
            "&:focus": {
              outline: "none",
            },
          },
        },
      },
      MuiIconButton: {
        styleOverrides: {
          root: {
            "&:focus": {
              outline: "none",
            },
          },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: {
            fontSize: "0.875rem",
          },
        },
      },
    },
  });

  return (
    <MuiThemeProvider theme={theme}>
      <CssBaseline />
      <ConnectionProvider>
        <Router>
          <Routes>
            <Route path="/login" element={<Login/>} />
            <Route path="/authorize" element={<OIDC/>} />
            <Route path="/*" element={
              <MainLayout>
                <AppRoutes />
              </MainLayout>
            } />
          </Routes>
        </Router>
      </ConnectionProvider>
    </MuiThemeProvider>
  );
}

function App() {
  return (
    <ThemeProvider>
      <AppContent />
    </ThemeProvider>
  );
}

export default App;
