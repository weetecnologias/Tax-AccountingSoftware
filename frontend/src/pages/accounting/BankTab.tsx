import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Upload } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { EmptyState, ErrorState, LoadingRows, StatusBadge } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Select, Table, Td, Th } from "@/components/ui";
import { useAccounts } from "@/hooks/useAccounting";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatINR } from "@/lib/money";
import { formatDate, formatDateTime } from "@/lib/utils";
import type { BankImport } from "@/types/accounting";

export const BANK_LABELS: Record<string, string> = { HDFC: "HDFC Bank", ICICI: "ICICI Bank", SBI: "SBI", AXIS: "Axis Bank", KOTAK: "Kotak", GENERIC: "Generic" };

export function BankTab({ clientId, closed }: { clientId: string; closed: boolean }) {
  const { can } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const list = useQuery({ queryKey: ["accounting", clientId, "bank"], queryFn: () => api.get<BankImport[]>(`/bank-reconciliation/statements?clientId=${clientId}`) });
  return (
    <Card>
      <CardHeader title="Bank reconciliation" description="Import a statement, match it with posted entries, then have a reviewer sign off."
        actions={can("ACCOUNTING_WRITE") && !closed && <Button size="sm" onClick={() => setOpen(true)}><Upload className="h-4 w-4" /> Import statement</Button>} />
      {list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>Statement</Th><Th>Bank ledger</Th><Th>Period</Th><Th className="text-right">Closing balance</Th><Th>Progress</Th><Th>Status</Th></tr></thead>
            <tbody>
              {list.isPending ? <LoadingRows cols={6} rows={2} /> : list.data?.map((s) => (
                <tr key={s.id} className="cursor-pointer hover:bg-mint/40" onClick={() => navigate(`/accounting/bank/${s.id}`)}>
                  <Td><p className="font-medium">{s.originalFilename}</p><p className="text-xs text-muted">{BANK_LABELS[s.bankFormat] ?? s.bankFormat} layout · imported {formatDateTime(s.importedAt)}</p></Td>
                  <Td className="text-xs">{s.ledgerAccountName}</Td>
                  <Td className="whitespace-nowrap">{formatDate(s.periodFrom)} – {formatDate(s.periodTo)}</Td>
                  <Td className="text-right font-mono">{s.closingBalance ? formatINR(s.closingBalance) : "—"}</Td>
                  <Td className="text-xs">
                    <Badge tone="success">{s.matched} matched</Badge>{" "}
                    {s.unmatched > 0 && <Badge tone="warning">{s.unmatched} open</Badge>}{" "}
                    {s.excluded > 0 && <Badge>{s.excluded} excluded</Badge>}
                  </Td>
                  <Td><StatusBadge status={s.status === "RECONCILED" ? "APPROVED" : "REVIEW_REQUIRED"} /></Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {list.data?.length === 0 && <EmptyState title="No statements imported" description="CSV, XLS and XLSX exports from HDFC, ICICI, SBI, Axis, Kotak and most other banks are supported." />}
        </>
      )}
      {open && <ImportDialog clientId={clientId} onClose={() => setOpen(false)} onDone={(id) => navigate(`/accounting/bank/${id}`)} />}
    </Card>
  );
}

function ImportDialog({ clientId, onClose, onDone }: { clientId: string; onClose: () => void; onDone: (id: string) => void }) {
  const queryClient = useQueryClient();
  const accounts = useAccounts(clientId);
  // Bank ledgers first (and selected by default), then other asset ledgers such as cash.
  const banks = (accounts.data ?? [])
    .filter((a) => !a.group && a.active && a.accountType === "ASSET" && (a.subtype === "BANK" || a.subtype === "CASH"))
    .sort((a, b) => (a.subtype === b.subtype ? a.code.localeCompare(b.code) : a.subtype === "BANK" ? -1 : 1));
  const [ledger, setLedger] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const upload = useMutation({
    mutationFn: () => {
      const fd = new FormData();
      fd.append("file", file!);
      fd.append("clientId", clientId);
      fd.append("ledgerAccountId", ledger || banks[0]?.id || "");
      return api.post<{ statement: BankImport; autoMatched: number }>("/bank-reconciliation/statements", fd);
    },
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ["accounting", clientId, "bank"] });
      toast.success(`${r.statement.lineCount} transactions imported (${BANK_LABELS[r.statement.bankFormat] ?? r.statement.bankFormat} layout), ${r.autoMatched} matched automatically`);
      for (const w of r.statement.warnings) toast.warning(w);
      onDone(r.statement.id);
    },
    onError: (e) => setError(e instanceof ApiError ? (e.fieldErrors[0]?.message ?? e.message) : "Import failed"),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="Import bank statement"
      description="The importer finds the transaction table by its column headings, so most bank exports work without changes."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={upload.isPending} disabled={!file} onClick={() => { setError(null); upload.mutate(); }}><Upload className="h-4 w-4" /> Import</Button></>}>
      <div className="space-y-4">
        <Field label="Bank ledger" htmlFor="b-ledger" hint="The chart-of-accounts ledger this statement belongs to">
          <Select id="b-ledger" value={ledger || banks[0]?.id || ""} onChange={(e) => setLedger(e.target.value)}>
            {banks.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
          </Select>
        </Field>
        <Field label="Statement file" htmlFor="b-file" required error={error ?? undefined}>
          <input id="b-file" type="file" accept=".csv,.xls,.xlsx,.txt" onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="block w-full text-sm file:mr-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-mint file:px-3 file:py-2 file:text-sm file:font-medium file:text-primary" />
        </Field>
        <p className="text-xs text-muted">Re-importing an overlapping statement only adds transactions that are not already present.</p>
      </div>
    </Dialog>
  );
}
