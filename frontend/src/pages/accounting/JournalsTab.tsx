import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { EmptyState, ErrorState, LoadingRows, Pagination, StatusBadge } from "@/components/common";
import { Badge, Button, Card, Input, Select, Table, Td, Th } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatINR } from "@/lib/money";
import { formatDate, humanize } from "@/lib/utils";
import type { Page } from "@/types/api";
import type { JournalSummary } from "@/types/accounting";

const STATUSES = ["DRAFT", "PENDING_APPROVAL", "APPROVED", "POSTED", "CANCELLED"];

export function JournalsTab({ clientId, active }: { clientId: string; active: boolean }) {
  const { can } = useAuth();
  const navigate = useNavigate();
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const journals = useQuery({
    queryKey: ["accounting", clientId, "journals", status, q, page],
    queryFn: () => api.get<Page<JournalSummary>>(`/accounting/journals${qs({ clientId, status, q, page, size: 20 })}`),
    placeholderData: keepPreviousData,
  });

  return (
    <Card>
      <div className="flex flex-wrap gap-3 border-b border-line p-4">
        <div className="relative min-w-56 flex-1">
          <Search className="pointer-events-none absolute top-2.5 left-3 h-4 w-4 text-muted" aria-hidden />
          <Input aria-label="Search journals" placeholder="Search number, narration or reference" value={q}
            onChange={(e) => { setQ(e.target.value); setPage(0); }} className="pl-9" />
        </div>
        <Select aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="w-52">
          <option value="">All statuses</option>
          {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </Select>
        {can("ACCOUNTING_WRITE") && (
          <Button onClick={() => navigate(`/accounting/journals/new?clientId=${clientId}`)} disabled={!active}
            title={active ? undefined : "The client must be active"}>
            <Plus className="h-4 w-4" /> New journal
          </Button>
        )}
      </div>
      {journals.isError ? <ErrorState error={journals.error} onRetry={() => journals.refetch()} /> : (
        <>
          <Table>
            <thead>
              <tr><Th>Number</Th><Th>Date</Th><Th>Narration</Th><Th className="text-right">Debit</Th><Th className="text-right">Credit</Th><Th>Status</Th><Th>Prepared by</Th></tr>
            </thead>
            <tbody>
              {journals.isPending ? <LoadingRows cols={7} /> : journals.data?.items.map((j) => (
                <tr key={j.id} className="cursor-pointer hover:bg-mint/40" onClick={() => navigate(`/accounting/journals/${j.id}`)}>
                  <Td className="font-mono text-xs whitespace-nowrap">
                    {j.entryNumber ?? <span className="text-muted">Draft</span>}
                    {j.entryType !== "GENERAL" && <Badge className="ml-1.5">{humanize(j.entryType)}</Badge>}
                  </Td>
                  <Td className="whitespace-nowrap">{formatDate(j.entryDate)}</Td>
                  <Td>
                    <p className="max-w-md truncate">{j.narration}</p>
                    {j.reversedById && <p className="text-xs text-muted">Reversed</p>}
                    {!j.balanced && j.status === "DRAFT" && <p className="text-xs text-danger">Not balanced</p>}
                  </Td>
                  <Td className="text-right font-mono tabular-nums">{formatINR(j.totalDebit)}</Td>
                  <Td className="text-right font-mono tabular-nums">{formatINR(j.totalCredit)}</Td>
                  <Td><StatusBadge status={j.status} /></Td>
                  <Td className="text-muted">{j.preparedBy?.fullName}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {journals.data?.items.length === 0 && <EmptyState title="No journals" description={active ? "Record the first journal entry." : undefined} />}
          {journals.data && <Pagination page={journals.data.page} totalPages={journals.data.totalPages} totalItems={journals.data.totalItems} onPage={setPage} />}
        </>
      )}
    </Card>
  );
}
