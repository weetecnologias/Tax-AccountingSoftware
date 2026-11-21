import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Download, Plus, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { EmptyState, ErrorState, LoadingRows, PageHeader, Pagination, StatusBadge } from "@/components/common";
import { Button, Card, Input, Select, Table, Td, Th } from "@/components/ui";
import { labelOf, useEntityTypes } from "@/hooks/useReference";
import { api, downloadFile, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, humanize } from "@/lib/utils";
import { CLIENT_STATUSES, type ClientSummary, type Page } from "@/types/api";

export function ClientsPage() {
  const { can } = useAuth();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get("q") ?? "");
  const status = params.get("status") ?? "";
  const entityType = params.get("entityType") ?? "";
  const page = Number(params.get("page") ?? 0);
  const entityTypes = useEntityTypes();

  useEffect(() => {
    const t = setTimeout(() => {
      if ((params.get("q") ?? "") !== search) update({ q: search, page: "" });
    }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) v ? p.set(k, v) : p.delete(k);
    setParams(p, { replace: true });
  }

  const filters = { q: params.get("q") ?? "", status, entityType };
  const query = useQuery({
    queryKey: ["clients", filters, page],
    queryFn: () => api.get<Page<ClientSummary>>(`/clients${qs({ ...filters, page, size: 20 })}`),
    placeholderData: keepPreviousData,
  });

  const exportCsv = async () => {
    try {
      await downloadFile(`/clients/export${qs(filters)}`, "clients.csv");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Export failed");
    }
  };

  return (
    <div>
      <PageHeader
        title="Clients"
        description="Taxpayers and businesses serviced by the organisation"
        actions={
          <>
            {can("CLIENT_EXPORT") && (
              <Button variant="secondary" onClick={exportCsv}>
                <Download className="h-4 w-4" /> Export CSV
              </Button>
            )}
            {can("CLIENT_WRITE") && (
              <Button onClick={() => navigate("/clients/new")}>
                <Plus className="h-4 w-4" /> New client
              </Button>
            )}
          </>
        }
      />
      <Card>
        <div className="flex flex-wrap gap-3 border-b border-line p-4">
          <div className="relative min-w-60 flex-1">
            <Search className="pointer-events-none absolute top-2.5 left-3 h-4 w-4 text-muted" aria-hidden />
            <Input
              aria-label="Search clients"
              placeholder="Search by name, client code or exact PAN"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9"
            />
          </div>
          <Select aria-label="Filter by status" value={status} onChange={(e) => update({ status: e.target.value, page: "" })}
            className="w-48">
            <option value="">All statuses</option>
            {CLIENT_STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <Select aria-label="Filter by entity type" value={entityType}
            onChange={(e) => update({ entityType: e.target.value, page: "" })} className="w-56">
            <option value="">All entity types</option>
            {entityTypes.data?.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </Select>
        </div>
        {query.isError ? (
          <ErrorState error={query.error} onRetry={() => query.refetch()} />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Code</Th>
                  <Th>Client</Th>
                  <Th>Entity type</Th>
                  <Th>PAN</Th>
                  <Th>Status</Th>
                  <Th>Accountant</Th>
                  <Th>Created</Th>
                </tr>
              </thead>
              <tbody>
                {query.isPending ? <LoadingRows cols={7} /> : query.data?.items.map((c) => (
                  <tr key={c.id} className="cursor-pointer hover:bg-mint/40" onClick={() => navigate(`/clients/${c.id}`)}>
                    <Td className="font-mono text-xs">{c.clientCode}</Td>
                    <Td>
                      <Link to={`/clients/${c.id}`} className="font-medium text-primary hover:underline"
                        onClick={(e) => e.stopPropagation()}>
                        {c.displayName}
                      </Link>
                      <p className="text-xs text-muted">{c.legalName}</p>
                    </Td>
                    <Td>{labelOf(entityTypes.data, c.entityType)}</Td>
                    <Td className="font-mono text-xs">{c.panMasked}</Td>
                    <Td><StatusBadge status={c.status} /></Td>
                    <Td>{c.accountant?.fullName ?? <span className="text-muted">Unassigned</span>}</Td>
                    <Td className="text-muted">{formatDate(c.createdAt)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            {query.data && query.data.items.length === 0 && (
              <EmptyState
                title={filters.q || status || entityType ? "No clients match these filters" : "No clients yet"}
                description={filters.q || status || entityType ? "Try clearing a filter." : "Create the first client to begin onboarding."}
                action={can("CLIENT_WRITE") && !filters.q && !status && !entityType
                  ? <Button onClick={() => navigate("/clients/new")}><Plus className="h-4 w-4" /> New client</Button> : undefined}
              />
            )}
            {query.data && (
              <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems}
                onPage={(p) => update({ page: String(p) })} />
            )}
          </>
        )}
      </Card>
    </div>
  );
}
