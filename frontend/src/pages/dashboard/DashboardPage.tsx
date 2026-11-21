import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Clock, Copy, FileText, UserX, Users2 } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { ErrorState, PageHeader, Skeleton } from "@/components/common";
import { Card, CardHeader } from "@/components/ui";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, humanize } from "@/lib/utils";
import type { DashboardSummary } from "@/types/api";

const STATUS_COLORS: Record<string, string> = {
  DRAFT: "#9aa5a0",
  DOCUMENTS_PENDING: "#E9A23B",
  UNDER_VERIFICATION: "#d4912f",
  READY_FOR_APPROVAL: "#4f9d86",
  ACTIVE: "#26846B",
  ON_HOLD: "#c98a2c",
  CLOSED: "#D9534F",
  UPLOADED: "#E9A23B",
  DUPLICATE_SUSPECTED: "#D9534F",
  REVIEW_REQUIRED: "#d4912f",
  APPROVED: "#26846B",
  REJECTED: "#b94a47",
  ARCHIVED: "#9aa5a0",
};

function Metric({ label, value, icon, tone = "default", to }: {
  label: string; value: number; icon: ReactNode; tone?: "default" | "warning" | "danger" | "success"; to?: string;
}) {
  const toneClass = { default: "bg-mint text-primary", warning: "bg-warning-soft text-[#8a5a12]",
    danger: "bg-danger-soft text-danger", success: "bg-mint text-emerald" }[tone];
  const body = (
    <Card className="flex items-center gap-4 p-4 transition-shadow hover:shadow-md">
      <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${toneClass}`}>{icon}</div>
      <div className="min-w-0">
        <p className="text-2xl font-semibold tabular-nums">{value.toLocaleString("en-IN")}</p>
        <p className="truncate text-xs text-muted">{label}</p>
      </div>
    </Card>
  );
  return to ? <Link to={to} className="block rounded-card">{body}</Link> : body;
}

export function DashboardPage() {
  const { user, can } = useAuth();
  const q = useQuery({ queryKey: ["dashboard"], queryFn: () => api.get<DashboardSummary>("/dashboard/summary"),
    refetchInterval: 60_000 });

  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;

  const clientBars = d ? Object.entries(d.clientsByStatus).map(([k, v]) => ({ status: k, label: humanize(k), count: v })) : [];
  const docBars = d ? Object.entries(d.documentsByStatus).map(([k, v]) => ({ status: k, label: humanize(k), count: v })) : [];
  const uploads = d ? d.uploadsLast14Days.map((u) => ({ ...u, label: u.day.slice(5) })) : [];

  return (
    <div>
      <PageHeader title={`Welcome, ${user?.fullName.split(" ")[0] ?? ""}`}
        description={d ? `Live figures · updated ${formatDateTime(d.generatedAt)}` : "Loading live figures…"} />

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {!d ? Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} />) : (
          <>
            <Metric label="Total clients" value={d.totalClients} icon={<Users2 className="h-5 w-5" />} to="/clients" />
            <Metric label="Active clients" value={d.activeClients} icon={<CheckCircle2 className="h-5 w-5" />} tone="success"
              to="/clients?status=ACTIVE" />
            <Metric label="Clients awaiting approval" value={d.clientsAwaitingApproval} icon={<Clock className="h-5 w-5" />}
              tone={d.clientsAwaitingApproval ? "warning" : "default"} to="/clients?status=READY_FOR_APPROVAL" />
            <Metric label="Open clients without an accountant" value={d.clientsWithoutAccountant}
              icon={<UserX className="h-5 w-5" />} tone={d.clientsWithoutAccountant ? "warning" : "default"} />
            <Metric label="Documents stored" value={d.totalDocuments} icon={<FileText className="h-5 w-5" />} to="/documents" />
            <Metric label="Documents awaiting review" value={d.documentsAwaitingReview} icon={<AlertTriangle className="h-5 w-5" />}
              tone={d.documentsAwaitingReview ? "warning" : "default"} to="/documents?status=UPLOADED" />
            <Metric label="Suspected duplicates" value={d.suspectedDuplicates} icon={<Copy className="h-5 w-5" />}
              tone={d.suspectedDuplicates ? "danger" : "default"} to="/documents?status=DUPLICATE_SUSPECTED" />
            <Metric label="Documents uploaded today" value={d.documentsUploadedToday} icon={<FileText className="h-5 w-5" />} />
          </>
        )}
      </div>

      <div className="mt-6 grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader title="Clients by status" description="Onboarding pipeline" />
          <div className="h-64 p-4">
            {d && <StatusChart data={clientBars} />}
          </div>
        </Card>
        <Card>
          <CardHeader title="Documents by status" />
          <div className="h-64 p-4">
            {d && <StatusChart data={docBars} />}
          </div>
        </Card>
        <Card>
          <CardHeader title="Uploads, last 14 days" description="India Standard Time" />
          <div className="h-64 p-4">
            {d && (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={uploads}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e7e4" />
                  <XAxis dataKey="label" tick={{ fontSize: 11, fill: "#69756F" }} />
                  <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: "#69756F" }} width={30} />
                  <Tooltip cursor={{ fill: "#E8F3EE" }} />
                  <Bar dataKey="count" name="Uploads" fill="#26846B" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </Card>
        {can("AUDIT_READ") && (
          <Card>
            <CardHeader title="Recent activity" actions={<Link to="/audit" className="text-sm text-emerald hover:underline">View all</Link>} />
            <ul className="divide-y divide-line">
              {d?.recentActivity.length === 0 && <li className="px-5 py-6 text-sm text-muted">No activity yet.</li>}
              {d?.recentActivity.map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-3 px-5 py-2.5 text-sm">
                  <span className="truncate">
                    <span className="font-medium">{humanize(a.action)}</span>
                    <span className="text-muted"> · {a.actorEmail ?? "system"}</span>
                  </span>
                  <span className="shrink-0 text-xs text-muted">{formatDateTime(a.occurredAt)}</span>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </div>
      <p className="mt-6 text-xs text-muted">
        GST, income-tax, reconciliation and AI metrics will appear here as those modules are delivered.
      </p>
    </div>
  );
}

function StatusChart({ data }: { data: { status: string; label: string; count: number }[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} layout="vertical" margin={{ left: 20 }}>
        <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#e2e7e4" />
        <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11, fill: "#69756F" }} />
        <YAxis type="category" dataKey="label" width={130} tick={{ fontSize: 11, fill: "#69756F" }} />
        <Tooltip cursor={{ fill: "#E8F3EE" }} />
        <Bar dataKey="count" name="Count" radius={[0, 4, 4, 0]}>
          {data.map((e) => <Cell key={e.status} fill={STATUS_COLORS[e.status] ?? "#26846B"} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
