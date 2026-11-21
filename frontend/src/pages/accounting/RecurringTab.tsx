import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Plus, Repeat, Trash2, XCircle } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { EmptyState, ErrorState, Skeleton } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Textarea, Th } from "@/components/ui";
import { postableAccounts, useAccounts } from "@/hooks/useAccounting";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatINR, isValidAmount, paiseToString, toPaise } from "@/lib/money";
import { formatDate } from "@/lib/utils";
import type { GenerateResult, RecurringFrequency, RecurringTemplate } from "@/types/accounting";
import { partiesFor, partyKind } from "./JournalPage";
import { useParties } from "./PartiesTab";

const FREQ: Record<RecurringFrequency, string> = { MONTHLY: "Monthly", QUARTERLY: "Quarterly", YEARLY: "Yearly" };

export function RecurringTab({ clientId, active }: { clientId: string; active: boolean }) {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const list = useQuery({
    queryKey: ["accounting", clientId, "recurring"],
    queryFn: () => api.get<RecurringTemplate[]>(`/accounting/recurring?clientId=${clientId}`),
  });
  const accounts = useAccounts(clientId);
  const [editing, setEditing] = useState<RecurringTemplate | "new" | null>(null);
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [result, setResult] = useState<GenerateResult | null>(null);
  const generate = useMutation({
    mutationFn: () => api.post<GenerateResult>("/accounting/recurring/generate", { clientId, asOf }),
    onSuccess: (r) => {
      setResult(r);
      queryClient.invalidateQueries({ queryKey: ["accounting"] });
      toast.success(r.createdJournalIds.length === 0 ? "Nothing new to generate" : `${r.createdJournalIds.length} draft journal(s) created`);
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });
  const canWrite = can("ACCOUNTING_WRITE");
  const nameOf = (id: string) => accounts.data?.find((a) => a.id === id);

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader title="Recurring journals" description="Templates for entries that repeat, such as rent or depreciation. Generating creates drafts that still need approval and posting."
          actions={canWrite && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> New template</Button>} />
        {list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : list.isPending ? <Skeleton className="m-4 h-24" /> : list.data.length === 0 ? (
          <EmptyState title="No recurring journals" description="Create a template once and generate the drafts each month." />
        ) : (
          <Table>
            <thead><tr><Th>Name</Th><Th>Frequency</Th><Th>Next date</Th><Th>Ends</Th><Th>Accounts</Th><Th className="text-right">Amount</Th><Th /></tr></thead>
            <tbody>
              {list.data.map((t) => (
                <tr key={t.id}>
                  <Td><p className="font-medium">{t.name}</p><p className="max-w-xs truncate text-xs text-muted">{t.narration}</p></Td>
                  <Td>{FREQ[t.frequency]}</Td>
                  <Td className="whitespace-nowrap">{t.active ? formatDate(t.nextDate) : <Badge>Paused</Badge>}</Td>
                  <Td className="whitespace-nowrap text-muted">{t.endDate ? formatDate(t.endDate) : "No end"}</Td>
                  <Td className="text-xs text-muted">{t.lines.map((l) => nameOf(l.accountId)?.name).filter(Boolean).join(" / ")}</Td>
                  <Td className="text-right font-mono">{formatINR(t.amount)}</Td>
                  <Td className="text-right">{canWrite && <Button size="sm" variant="secondary" onClick={() => setEditing(t)}>Edit</Button>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {canWrite && (
        <Card className="p-4">
          <div className="flex flex-wrap items-end gap-3">
            <Repeat className="mb-2.5 h-4 w-4 text-emerald" />
            <Field label="Generate everything due up to" htmlFor="rec-asof"><Input id="rec-asof" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></Field>
            <Button loading={generate.isPending} disabled={!active || !list.data?.length} onClick={() => generate.mutate()}>Generate drafts</Button>
            {!active && <p className="pb-2 text-sm text-muted">The client must be active.</p>}
          </div>
          <p className="mt-2 text-xs text-muted">Running it twice for the same date never creates duplicates. Dates in a closed period or outside an open financial year are skipped and listed.</p>
          {result && (
            <div className="mt-3 space-y-1 text-sm">
              {result.createdJournalIds.length > 0 && (
                <p className="flex flex-wrap items-center gap-2 text-emerald"><CheckCircle2 className="h-4 w-4" /> Created:
                  {result.createdJournalIds.map((id, i) => <Link key={id} to={`/accounting/journals/${id}`} className="underline">draft {i + 1}</Link>)}
                </p>
              )}
              {result.skipped.map((s) => <p key={s} className="flex items-center gap-2 text-[#8a5a12]"><XCircle className="h-4 w-4" /> Skipped {s}</p>)}
            </div>
          )}
        </Card>
      )}

      {editing && <TemplateDialog clientId={clientId} existing={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

type LineState = { accountId: string; partyId: string; debit: string; credit: string; description: string };
const blank = (): LineState => ({ accountId: "", partyId: "", debit: "", credit: "", description: "" });

function TemplateDialog({ clientId, existing, onClose }: { clientId: string; existing: RecurringTemplate | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const accounts = useAccounts(clientId);
  const ledgers = useMemo(() => postableAccounts(accounts.data), [accounts.data]);
  const parties = useParties(clientId);
  const [name, setName] = useState(existing?.name ?? "");
  const [narration, setNarration] = useState(existing?.narration ?? "");
  const [reference, setReference] = useState(existing?.reference ?? "");
  const [frequency, setFrequency] = useState<RecurringFrequency>(existing?.frequency ?? "MONTHLY");
  const [nextDate, setNextDate] = useState(existing?.nextDate ?? "");
  const [endDate, setEndDate] = useState(existing?.endDate ?? "");
  const [isActive, setIsActive] = useState(existing?.active ?? true);
  const [lines, setLines] = useState<LineState[]>(existing ? existing.lines.map((l) => ({
    accountId: l.accountId, partyId: l.partyId ?? "", debit: l.debit === "0.00" ? "" : l.debit, credit: l.credit === "0.00" ? "" : l.credit,
    description: l.description ?? "" })) : [blank(), blank()]);
  const [error, setError] = useState<string | null>(null);

  const set = (i: number, patch: Partial<LineState>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  let dr = 0n, cr = 0n, invalid = false;
  for (const l of lines) {
    if (!isValidAmount(l.debit) || !isValidAmount(l.credit)) invalid = true;
    dr += toPaise(l.debit) ?? 0n;
    cr += toPaise(l.credit) ?? 0n;
  }
  const balanced = !invalid && dr > 0n && dr === cr;

  const save = useMutation({
    mutationFn: () => {
      const body = {
        clientId, name: name.trim(), narration: narration.trim(), reference: reference.trim() || null, frequency, nextDate,
        endDate: endDate || null, active: isActive, version: existing?.version,
        lines: lines.map((l) => ({ accountId: l.accountId, debit: l.debit.replace(/,/g, "") || null, credit: l.credit.replace(/,/g, "") || null,
          description: l.description.trim() || null, partyId: partyKind(ledgers.find((a) => a.id === l.accountId)) && l.partyId ? l.partyId : null })),
      };
      return existing ? api.put(`/accounting/recurring/${existing.id}`, body) : api.post("/accounting/recurring", body);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["accounting", clientId, "recurring"] });
      toast.success(existing ? "Template updated" : "Template created");
      onClose();
    },
    onError: (e) => setError(e instanceof ApiError && e.fieldErrors.length ? e.fieldErrors.map((f) => f.message).join(" ") : e instanceof Error ? e.message : "Failed"),
  });

  const submit = () => {
    if (!name.trim() || !narration.trim() || !nextDate) { setError("Name, narration and the next date are required."); return; }
    if (lines.some((l) => !l.accountId)) { setError("Choose an account on every line."); return; }
    if (!balanced) { setError("Debits and credits must be equal and more than zero."); return; }
    setError(null);
    save.mutate();
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? `Edit ${existing.name}` : "New recurring journal"}
      description="Each run creates a draft journal on the next date and moves the next date forward by the frequency."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={submit}>Save template</Button></>}>
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name" htmlFor="rt-name" required><Input id="rt-name" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Reference" htmlFor="rt-ref"><Input id="rt-ref" value={reference} maxLength={100} onChange={(e) => setReference(e.target.value)} /></Field>
          <Field label="Narration" htmlFor="rt-narr" required className="sm:col-span-2"><Textarea id="rt-narr" rows={2} value={narration} maxLength={1000} onChange={(e) => setNarration(e.target.value)} /></Field>
          <Field label="Frequency" htmlFor="rt-freq">
            <Select id="rt-freq" value={frequency} onChange={(e) => setFrequency(e.target.value as RecurringFrequency)}>
              {Object.entries(FREQ).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </Field>
          <Field label="Next date" htmlFor="rt-next" required><Input id="rt-next" type="date" value={nextDate} onChange={(e) => setNextDate(e.target.value)} /></Field>
          <Field label="End date (optional)" htmlFor="rt-end"><Input id="rt-end" type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} /></Field>
          <label className="flex items-center gap-2 self-end pb-2 text-sm">
            <input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} className="h-4 w-4 accent-emerald" /> Active
          </label>
        </div>
        <div className="space-y-2">
          {lines.map((l, i) => {
            const kind = partyKind(ledgers.find((a) => a.id === l.accountId));
            return (
              <div key={i} className="grid grid-cols-[1fr_7rem_7rem_2rem] items-start gap-2">
                <div className="space-y-1">
                  <Select aria-label={`Line ${i + 1} account`} value={l.accountId} onChange={(e) => set(i, { accountId: e.target.value, partyId: "" })}>
                    <option value="">Account…</option>
                    {ledgers.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
                  </Select>
                  {kind && (
                    <Select aria-label={`Line ${i + 1} party`} value={l.partyId} onChange={(e) => set(i, { partyId: e.target.value })}>
                      <option value="">{kind === "CUSTOMER" ? "Customer…" : "Vendor…"}</option>
                      {partiesFor(parties.data, kind).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </Select>
                  )}
                </div>
                <Input aria-label={`Line ${i + 1} debit`} placeholder="Debit" inputMode="decimal" className="text-right font-mono" value={l.debit} onChange={(e) => set(i, { debit: e.target.value })} />
                <Input aria-label={`Line ${i + 1} credit`} placeholder="Credit" inputMode="decimal" className="text-right font-mono" value={l.credit} onChange={(e) => set(i, { credit: e.target.value })} />
                <Button variant="ghost" size="icon" aria-label={`Remove line ${i + 1}`} disabled={lines.length <= 2} onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
              </div>
            );
          })}
          <div className="flex items-center justify-between">
            <Button size="sm" variant="secondary" onClick={() => setLines((ls) => [...ls, blank()])}><Plus className="h-4 w-4" /> Add line</Button>
            <p className={`text-sm ${balanced ? "text-emerald" : "text-muted"}`}>Dr {formatINR(paiseToString(dr))} · Cr {formatINR(paiseToString(cr))}</p>
          </div>
        </div>
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      </div>
    </Dialog>
  );
}
