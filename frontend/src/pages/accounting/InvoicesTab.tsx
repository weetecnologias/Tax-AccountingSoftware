import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, Settings2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { EmptyState, ErrorState, LoadingRows, Pagination, StatusBadge } from "@/components/common";
import { Button, Card, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatINR, paiseToString, toPaise } from "@/lib/money";
import { formatDate, humanize } from "@/lib/utils";
import type { Page } from "@/types/api";
import type { Invoice, InvoiceSeries, InvoiceType } from "@/types/accounting";

const TAB_LABEL: Record<InvoiceType, string> = { SALES: "Sales", PURCHASE: "Purchases", CREDIT_NOTE: "Credit notes", DEBIT_NOTE: "Debit notes" };
const KIND_LABEL = { INVOICE: "Invoices", CREDIT_NOTE: "Credit notes", DEBIT_NOTE: "Debit notes" } as const;

export function InvoicesTab({ clientId, active }: { clientId: string; active: boolean }) {
  const { can } = useAuth();
  const navigate = useNavigate();
  const [type, setType] = useState<InvoiceType>("SALES");
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [seriesOpen, setSeriesOpen] = useState(false);
  const list = useQuery({
    queryKey: ["accounting", clientId, "invoices", type, status, q, page],
    queryFn: () => api.get<Page<Invoice>>(`/invoices${qs({ clientId, type, status, q, page, size: 20 })}`),
    placeholderData: keepPreviousData,
  });

  return (
    <Card>
      <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
        <div className="flex rounded-md border border-line p-0.5" role="tablist" aria-label="Invoice type">
          {(["SALES", "PURCHASE", "CREDIT_NOTE", "DEBIT_NOTE"] as const).map((t) => (
            <button key={t} type="button" role="tab" aria-selected={type === t} onClick={() => { setType(t); setPage(0); }}
              className={`cursor-pointer rounded px-3 py-1.5 text-sm ${type === t ? "bg-primary text-white" : "text-muted hover:text-ink"}`}>
              {TAB_LABEL[t]}
            </button>
          ))}
        </div>
        <div className="relative min-w-48 flex-1">
          <Search className="pointer-events-none absolute top-2.5 left-3 h-4 w-4 text-muted" aria-hidden />
          <Input aria-label="Search invoices" placeholder="Search invoice number" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} className="pl-9" />
        </div>
        <Select aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="w-48">
          <option value="">All statuses</option>
          {["DRAFT", "PENDING_APPROVAL", "POSTED", "CANCELLED"].map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </Select>
        {type !== "PURCHASE" && <Button variant="secondary" size="sm" onClick={() => setSeriesOpen(true)}><Settings2 className="h-4 w-4" /> Numbering</Button>}
        {can("ACCOUNTING_WRITE") && (type === "SALES" || type === "PURCHASE") && (
          <Button onClick={() => navigate(`/accounting/invoices/new?clientId=${clientId}&type=${type}`)} disabled={!active}>
            <Plus className="h-4 w-4" /> New {type === "SALES" ? "sales" : "purchase"} invoice
          </Button>
        )}
      </div>
      {list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>Number</Th><Th>Date</Th><Th>{type === "SALES" || type === "CREDIT_NOTE" ? "Customer" : "Vendor"}</Th><Th>Supply</Th><Th className="text-right">Taxable</Th><Th className="text-right">GST</Th><Th className="text-right">Total</Th><Th>Status</Th></tr></thead>
            <tbody>
              {list.isPending ? <LoadingRows cols={8} /> : list.data?.items.map((i) => {
                const gst = paiseToString((toPaise(i.cgstTotal) ?? 0n) + (toPaise(i.sgstTotal) ?? 0n) + (toPaise(i.igstTotal) ?? 0n));
                return (
                  <tr key={i.id} className="cursor-pointer hover:bg-mint/40" onClick={() => navigate(`/accounting/invoices/${i.id}`)}>
                    <Td className="font-mono text-xs">{(type !== "PURCHASE" ? i.invoiceNumber : i.supplierInvoiceNumber) ?? <span className="text-muted">Draft</span>}
                      {i.originalNumber && <span className="block text-muted">against {i.originalNumber}</span>}</Td>
                    <Td className="whitespace-nowrap">{formatDate(i.invoiceDate)}</Td>
                    <Td>{i.partyName}{i.reverseCharge && <span className="ml-1 text-xs text-warning">RCM</span>}</Td>
                    <Td className="text-xs">{i.supplyType === "INTRA" ? "Intra-state" : "Inter-state"}</Td>
                    <Td className="text-right font-mono tabular-nums">{formatINR(i.taxableTotal)}</Td>
                    <Td className="text-right font-mono tabular-nums">{formatINR(gst)}</Td>
                    <Td className="text-right font-mono font-medium tabular-nums">{formatINR(i.grandTotal)}</Td>
                    <Td><StatusBadge status={i.status} /></Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
          {list.data?.items.length === 0 && <EmptyState title={`No ${TAB_LABEL[type].toLowerCase()}`}
            description={type === "CREDIT_NOTE" ? "Raise a credit note from a posted sales invoice." : type === "DEBIT_NOTE" ? "Raise a debit note from a posted purchase invoice." : undefined} />}
          {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
        </>
      )}
      {seriesOpen && <SeriesDialog clientId={clientId} onClose={() => setSeriesOpen(false)} />}
    </Card>
  );
}

function SeriesDialog({ clientId, onClose }: { clientId: string; onClose: () => void }) {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const series = useQuery({ queryKey: ["accounting", clientId, "series"], queryFn: () => api.get<InvoiceSeries[]>(`/invoices/series?clientId=${clientId}`) });
  const [edits, setEdits] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: async () => {
      for (const [id, prefix] of Object.entries(edits)) await api.put(`/invoices/series/${id}`, { prefix });
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["accounting", clientId, "series"] }); toast.success("Numbering updated"); onClose(); },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="Document numbering"
      description="Format PREFIX/YY-YY/00001, one series per GSTIN and document type, restarting each financial year. GST rules allow at most 16 characters, so the prefix is limited to 4."
      footer={<><Button variant="secondary" onClick={onClose}>Close</Button>{can("ACCOUNTING_APPROVE") && <Button loading={save.isPending} disabled={Object.keys(edits).length === 0} onClick={() => save.mutate()}>Save</Button>}</>}>
      <div className="space-y-3">
        {series.data?.map((s) => (
          <div key={s.id} className="flex items-end gap-3">
            <Field label={`${KIND_LABEL[s.kind]} · ${s.gstin ?? "no GSTIN"}`} htmlFor={`s-${s.id}`} className="w-56">
              <Input id={`s-${s.id}`} maxLength={4} className="font-mono uppercase" disabled={!can("ACCOUNTING_APPROVE")}
                value={edits[s.id] ?? s.prefix} onChange={(e) => setEdits({ ...edits, [s.id]: e.target.value.toUpperCase() })} />
            </Field>
            <p className="pb-2 text-sm text-muted">Next: <span className="font-mono text-ink">{(edits[s.id] ?? s.prefix) + s.nextNumberPreview.slice(s.prefix.length)}</span></p>
          </div>
        ))}
      </div>
    </Dialog>
  );
}
