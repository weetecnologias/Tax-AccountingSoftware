import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BookOpen, CalendarPlus, Search } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { EmptyState, ErrorState, PageHeader, Skeleton, StatusBadge } from "@/components/common";
import { Button, Card, CardHeader, Field, Input, Select, Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui";
import { useAccounts, useClientOptions, usePeriods } from "@/hooks/useAccounting";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { ClientDetail } from "@/types/api";
import { ChartTab } from "./ChartTab";
import { JournalsTab } from "./JournalsTab";
import { PeriodsTab } from "./PeriodsTab";
import { BalanceSheetTab, LedgerTab, ProfitLossTab, TrialBalanceTab } from "./ReportTabs";

function suggestFy(fyStartMonth: number): string {
  const now = new Date();
  const start = now.getMonth() + 1 >= fyStartMonth ? now.getFullYear() : now.getFullYear() - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, "0")}`;
}

export function AccountingPage() {
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const clientId = params.get("clientId");
  const tab = params.get("tab") ?? "journals";
  const [search, setSearch] = useState("");
  const clients = useClientOptions(search);
  const client = useQuery({
    queryKey: ["client", clientId],
    queryFn: () => api.get<ClientDetail>(`/clients/${clientId}`),
    enabled: !!clientId,
  });
  const accounts = useAccounts(clientId);
  const periods = usePeriods(clientId);

  const setParam = (k: string, v: string) => {
    const p = new URLSearchParams(params);
    p.set(k, v);
    setParams(p, { replace: true });
  };

  return (
    <div>
      <PageHeader title="Accounting" description="Double-entry books per client. Figures come only from posted journals." />
      <Card className="mb-5 p-4">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Client" htmlFor="acc-client" className="min-w-72 flex-1">
            <div className="flex gap-2">
              <div className="relative w-48">
                <Search className="pointer-events-none absolute top-2.5 left-3 h-4 w-4 text-muted" aria-hidden />
                <Input aria-label="Search clients" placeholder="Search" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
              </div>
              <Select id="acc-client" value={clientId ?? ""} onChange={(e) => setParams({ clientId: e.target.value, tab }, { replace: true })}
                className="flex-1">
                <option value="">Choose a client…</option>
                {clientId && client.data && !clients.data?.items.some((c) => c.id === clientId) && (
                  <option value={clientId}>{client.data.displayName} · {client.data.clientCode}</option>
                )}
                {clients.data?.items.map((c) => <option key={c.id} value={c.id}>{c.displayName} · {c.clientCode}</option>)}
              </Select>
            </div>
          </Field>
          {client.data && (
            <div className="flex items-center gap-2 pb-1.5 text-sm">
              <StatusBadge status={client.data.status} />
              <Link to={`/clients/${clientId}`} className="text-emerald hover:underline">Client profile</Link>
            </div>
          )}
        </div>
        {client.data && client.data.status !== "ACTIVE" && (
          <p className="mt-3 rounded-md bg-warning-soft px-3 py-2 text-sm text-[#8a5a12]">
            Journals can only be recorded once the client is active. You can still set up the chart of accounts and financial years.
          </p>
        )}
      </Card>

      {!clientId ? (
        <Card><EmptyState title="Choose a client" description="Select a client to see its books." /></Card>
      ) : accounts.isError ? (
        <ErrorState error={accounts.error} onRetry={() => accounts.refetch()} />
      ) : accounts.isPending || periods.isPending || client.isPending ? (
        <Skeleton className="h-64" />
      ) : accounts.data.length === 0 || periods.data?.length === 0 ? (
        <SetupCard clientId={clientId} needsChart={accounts.data.length === 0} needsYear={periods.data?.length === 0}
          fyStartMonth={client.data?.fyStartMonth ?? 4} canWrite={can("ACCOUNTING_WRITE")} closed={client.data?.status === "CLOSED"} />
      ) : (
        <Tabs value={tab} onValueChange={(v) => setParam("tab", v)}>
          <TabsList aria-label="Accounting sections">
            <TabsTrigger value="journals">Journals</TabsTrigger>
            <TabsTrigger value="trial-balance">Trial balance</TabsTrigger>
            <TabsTrigger value="profit-loss">Profit &amp; loss</TabsTrigger>
            <TabsTrigger value="balance-sheet">Balance sheet</TabsTrigger>
            <TabsTrigger value="ledger">General ledger</TabsTrigger>
            <TabsTrigger value="chart">Chart of accounts</TabsTrigger>
            <TabsTrigger value="periods">Periods</TabsTrigger>
          </TabsList>
          <TabsContent value="journals" className="mt-5"><JournalsTab clientId={clientId} active={client.data?.status === "ACTIVE"} /></TabsContent>
          <TabsContent value="trial-balance" className="mt-5"><TrialBalanceTab clientId={clientId} /></TabsContent>
          <TabsContent value="profit-loss" className="mt-5"><ProfitLossTab clientId={clientId} /></TabsContent>
          <TabsContent value="balance-sheet" className="mt-5"><BalanceSheetTab clientId={clientId} /></TabsContent>
          <TabsContent value="ledger" className="mt-5"><LedgerTab clientId={clientId} /></TabsContent>
          <TabsContent value="chart" className="mt-5"><ChartTab clientId={clientId} closed={client.data?.status === "CLOSED"} /></TabsContent>
          <TabsContent value="periods" className="mt-5"><PeriodsTab clientId={clientId} fyStartMonth={client.data?.fyStartMonth ?? 4} /></TabsContent>
        </Tabs>
      )}
    </div>
  );
}

function SetupCard({ clientId, needsChart, needsYear, fyStartMonth, canWrite, closed }: {
  clientId: string; needsChart: boolean; needsYear: boolean; fyStartMonth: number; canWrite: boolean; closed: boolean;
}) {
  const queryClient = useQueryClient();
  const [fy, setFy] = useState(suggestFy(fyStartMonth));
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["accounting", clientId] });
  const applyTemplate = useMutation({
    mutationFn: () => api.post("/accounting/chart-of-accounts/apply-template", { clientId }),
    onSuccess: () => { toast.success("Standard chart of accounts created"); refresh(); },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });
  const openYear = useMutation({
    mutationFn: () => api.post("/accounting/financial-years", { clientId, financialYear: fy }),
    onSuccess: () => { toast.success(`Financial year ${fy} opened`); refresh(); },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });

  if (!canWrite || closed) {
    return <Card><EmptyState title="Books not set up" description="An accountant needs to create the chart of accounts and open a financial year." /></Card>;
  }
  return (
    <Card>
      <CardHeader title="Set up the books" description="Two one-time steps before journals can be recorded." />
      <div className="grid gap-4 p-5 md:grid-cols-2">
        <div className="rounded-md border border-line p-4">
          <p className="flex items-center gap-2 font-medium"><BookOpen className="h-4 w-4 text-emerald" /> 1. Chart of accounts</p>
          {needsChart ? (
            <>
              <p className="mt-1 text-sm text-muted">Creates the standard Indian chart (Schedule III headings, GST input and output ledgers). You can add or rename accounts afterwards.</p>
              <Button className="mt-3" loading={applyTemplate.isPending} onClick={() => applyTemplate.mutate()}>Apply standard chart</Button>
            </>
          ) : <p className="mt-1 text-sm text-emerald">Done</p>}
        </div>
        <div className="rounded-md border border-line p-4">
          <p className="flex items-center gap-2 font-medium"><CalendarPlus className="h-4 w-4 text-emerald" /> 2. Financial year</p>
          {needsYear ? (
            <>
              <p className="mt-1 text-sm text-muted">Creates twelve monthly periods starting in the client's financial-year month.</p>
              <div className="mt-3 flex gap-2">
                <Input aria-label="Financial year" value={fy} onChange={(e) => setFy(e.target.value)} maxLength={7} className="w-28" />
                <Button loading={openYear.isPending} onClick={() => openYear.mutate()}>Open year</Button>
              </div>
            </>
          ) : <p className="mt-1 text-sm text-emerald">Done</p>}
        </div>
      </div>
    </Card>
  );
}
