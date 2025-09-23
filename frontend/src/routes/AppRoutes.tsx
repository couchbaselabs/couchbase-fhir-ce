import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Dashboard from "../pages/Dashboard/Dashboard";
import Buckets from "../pages/Buckets/Buckets";
import FhirResources from "../pages/FHIRResources/FhirResources";
import AuditLogs from "../pages/AuditLogs/AuditLogs";
import SystemLogs from "../pages/SystemLogs/Logs";
import ProtectedRoute from "./ProtectedRoute";
import Login from "../pages/Login";
import MainLayout from "../pages/Layout/MainLayout";

const AppRoutes: React.FC = () => {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      {/* <Route element={<ProtectedRoute />}> */}
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/buckets" element={<Buckets />} />
        <Route path="/fhir-resources" element={<FhirResources />} />
        <Route path="/auditlogs" element={<AuditLogs />} />
        <Route path="/systemlogs" element={<SystemLogs />} />
      {/* </Route> */}
      {/* Catch-all route for unmatched paths - redirect to dashboard */}
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
};

export default AppRoutes;
