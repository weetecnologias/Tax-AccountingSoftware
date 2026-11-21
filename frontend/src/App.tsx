import type { ReactNode } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { EmptyState } from "@/components/common";
import { AppLayout } from "@/layouts/AppLayout";
import { useAuth } from "@/lib/auth";
import { AuditLogPage } from "@/pages/audit/AuditLogPage";
import { ChangePasswordPage } from "@/pages/auth/ChangePasswordPage";
import { LoginPage } from "@/pages/auth/LoginPage";
import { ClientDetailPage } from "@/pages/clients/ClientDetailPage";
import { ClientFormPage } from "@/pages/clients/ClientFormPage";
import { ClientsPage } from "@/pages/clients/ClientsPage";
import { DashboardPage } from "@/pages/dashboard/DashboardPage";
import { DocumentsPage } from "@/pages/documents/DocumentsPage";
import { SettingsPage } from "@/pages/settings/SettingsPage";

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) {
    return (
      <div className="flex min-h-screen items-center justify-center text-sm text-muted" role="status">
        Loading…
      </div>
    );
  }
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (user.mustChangePassword && location.pathname !== "/change-password") {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
  const { can } = useAuth();
  if (!can(permission)) {
    return <EmptyState title="No access" description="You do not have permission to view this page." />;
  }
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/change-password" element={<RequireAuth><ChangePasswordPage /></RequireAuth>} />
      <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
        <Route index element={<RequirePermission permission="DASHBOARD_READ"><DashboardPage /></RequirePermission>} />
        <Route path="clients" element={<RequirePermission permission="CLIENT_READ"><ClientsPage /></RequirePermission>} />
        <Route path="clients/new" element={<RequirePermission permission="CLIENT_WRITE"><ClientFormPage /></RequirePermission>} />
        <Route path="clients/:id" element={<RequirePermission permission="CLIENT_READ"><ClientDetailPage /></RequirePermission>} />
        <Route path="clients/:id/edit" element={<RequirePermission permission="CLIENT_WRITE"><ClientFormPage /></RequirePermission>} />
        <Route path="documents" element={<RequirePermission permission="DOCUMENT_READ"><DocumentsPage /></RequirePermission>} />
        <Route path="audit" element={<RequirePermission permission="AUDIT_READ"><AuditLogPage /></RequirePermission>} />
        <Route path="settings" element={<RequirePermission permission="USER_READ"><SettingsPage /></RequirePermission>} />
        <Route path="*" element={<EmptyState title="Page not found" description="Check the address and try again." />} />
      </Route>
    </Routes>
  );
}
