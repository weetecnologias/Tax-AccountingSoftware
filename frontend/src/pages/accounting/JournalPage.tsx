import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, CheckCircle2, Plus, Trash2, XCircle } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useFieldArray, useForm, useWatch } from "react-hook-form";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import { ConfirmDialog, ErrorState, PageHeader, Skeleton, StatusBadge } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Textarea, Th } from "@/components/ui";
import { postableAccounts, useAccounts } from "@/hooks/useAccounting";
import { ApiError, api, request } from "@/lib/api";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { formatINR, isValidAmount, paiseToString, toPaise } from "@/lib/money";
import { formatDate, formatDateTime, humanize } from "@/lib/utils";
import type { JournalAction, JournalDetail } from "@/types/accounting";

const lineSchema = z.object({
  accountId: z.string().min(1, "Choose an account"),
  debit: z.string().refine(isValidAmount, "Max 2 decimals"),
  credit: z.string().refine(isValidAmount, "Max 2 decimals"),
  description: z.string().max(500),
}).refine((l) => (toPaise(l.debit) ?? 0n) > 0n !== (toPaise(l.credit) ?? 0n) > 0n, { path: ["debit"], message: "Enter a debit or a credit" });

const schema = z.object({
  entryDate: z.string().min(1, "Required"),
  entryType: z.enum(["GENERAL", "OPENING", "ADJUSTMENT"]),
  narration: z.string().trim().min(1, "Required").max(1000),
  reference: z.string().max(100),
  lines: z.array(lineSchema).min(2, "At least two lines"),
});
type Values = z.infer<typeof schema>;

const emptyLine = { accountId: "", debit: "", credit: "", description: "" };

export function JournalPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const isNew = !id;
  const journal = useQuery({
    queryKey: ["accounting", "journal", id],
    queryFn: () => api.get<JournalDetail>(`/accounting/journals/${id}`),
    enabled: !isNew,
  });
  const [editing, setEditing] = useState(false);

  if (!isNew && journal.isError) return <ErrorState error={journal.error} onRetry={() => journal.refetch()} />;
  if (!isNew && journal.isPending) return <Skeleton className="h-96" />;
  const clientId = isNew ? params.get("clientId") : journal.data!.clientId;
  if (!clientId) return <ErrorState error={new Error("Choose a client from the Accounting page first.")} />;

  if (isNew || editing) {
    return <JournalEditor clientId={clientId} existing={journal.data ?? null} onDone={() => setEditing(false)} />;
  }
  return <JournalView journal={journal.data!} onEdit={() => setEditing(true)} />;
}

// ------------------------------------------------------------------ editor

function JournalEditor({ clientId, existing, onDone }: { clientId: string; existing: JournalDetail | null; onDone: () => void }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accounts = useAccounts(clientId);
  const ledgers = useMemo(() => postableAccounts(accounts.data), [accounts.data]);
  const idempotencyKey = useRef(crypto.randomUUID());

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: existing ? {
      entryDate: existing.entryDate, entryType: existing.entryType === "REVERSAL" ? "GENERAL" : existing.entryType,
      narration: existing.narration, reference: existing.reference ?? "",
      lines: existing.lines.map((l) => ({ accountId: l.accountId, debit: l.debit === "0.00" ? "" : l.debit,
        credit: l.credit === "0.00" ? "" : l.credit, description: l.description ?? "" })),
    } : { entryDate: new Date().toISOString().slice(0, 10), entryType: "GENERAL", narration: "", reference: "", lines: [{ ...emptyLine }, { ...emptyLine }] },
  });
  const { register, control, handleSubmit, formState, setError } = form;
  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const lines = useWatch({ control, name: "lines" });

  // Account selects cannot show a saved value until the chart has loaded: re-apply values once it arrives.
  const { reset, getValues } = form;
  const synced = useRef(false);
  useEffect(() => {
    if (synced.current || !accounts.data) return;
    synced.current = true;
    reset(getValues(), { keepDirty: true, keepErrors: true });
  }, [accounts.data, reset, getValues]);
  const e = formState.errors;

  let dr = 0n, cr = 0n, invalid = false;
  for (const l of lines ?? []) {
    const d = toPaise(l.debit), c = toPaise(l.credit);
    if (d === null || c === null) invalid = true;
    dr += d ?? 0n;
    cr += c ?? 0n;
  }
  const balanced = !invalid && dr > 0n && dr === cr;
  const diff = dr - cr;

  const save = useMutation({
    mutationFn: async (v: Values) => {
      const body = {
        clientId, entryDate: v.entryDate, entryType: v.entryType, narration: v.narration, reference: nullIfBlank(v.reference),
        lines: v.lines.map((l) => ({ accountId: l.accountId, debit: l.debit.replace(/,/g, "") || null,
          credit: l.credit.replace(/,/g, "") || null, description: nullIfBlank(l.description) })),
        version: existing?.version,
      };
      if (existing) return api.put<JournalDetail>(`/accounting/journals/${existing.id}`, body);
      return fetchWithKey(body, idempotencyKey.current);
    },
    onSuccess: (j) => {
      queryClient.invalidateQueries({ queryKey: ["accounting"] });
      queryClient.setQueryData(["accounting", "journal", j.id], j);
      toast.success(existing ? "Draft updated" : "Draft saved");
      if (existing) onDone();
      else navigate(`/accounting/journals/${j.id}`, { replace: true });
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        for (const fe of err.fieldErrors) {
          const m = /^lines\[(\d+)\]\.(\w+)$/.exec(fe.field);
          if (m) setError(`lines.${Number(m[1])}.${m[2] as "debit"}`, { message: fe.message });
        }
      }
      applyServerErrors(err, setError, ["entryDate", "narration", "reference", "lines"]);
    },
  });

  return (
    <div>
      <PageHeader title={existing ? "Edit draft journal" : "New journal"} description="Save as a draft, then submit for approval. A different user must approve and post it."
        actions={<Button variant="secondary" onClick={() => (existing ? onDone() : navigate(-1))}><ArrowLeft className="h-4 w-4" /> Back</Button>} />
      <form onSubmit={handleSubmit((v) => save.mutate(v))} noValidate className="space-y-5">
        <Card>
          <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-4">
            <Field label="Date" htmlFor="j-date" required error={e.entryDate?.message}>
              <Input id="j-date" type="date" {...register("entryDate")} aria-invalid={!!e.entryDate} />
            </Field>
            <Field label="Type" htmlFor="j-type">
              <Select id="j-type" {...register("entryType")}>
                <option value="GENERAL">General</option><option value="OPENING">Opening balances</option><option value="ADJUSTMENT">Adjustment</option>
              </Select>
            </Field>
            <Field label="Reference" htmlFor="j-ref" hint="Invoice, voucher or bank reference"><Input id="j-ref" {...register("reference")} /></Field>
            <div />
            <Field label="Narration" htmlFor="j-narration" required error={e.narration?.message} className="md:col-span-4">
              <Textarea id="j-narration" rows={2} maxLength={1000} {...register("narration")} aria-invalid={!!e.narration} />
            </Field>
          </div>
        </Card>

        <Card>
          <CardHeader title="Lines" description="Each line is either a debit or a credit. Only ledger accounts are listed." />
          <Table>
            <thead><tr><Th className="w-10">#</Th><Th>Account</Th><Th>Line description</Th><Th className="w-40 text-right">Debit (₹)</Th><Th className="w-40 text-right">Credit (₹)</Th><Th className="w-10" /></tr></thead>
            <tbody>
              {fields.map((f, i) => {
                const le = e.lines?.[i];
                return (
                  <tr key={f.id} className="align-top">
                    <Td className="pt-5 text-muted">{i + 1}</Td>
                    <Td>
                      <Select aria-label={`Line ${i + 1} account`} {...register(`lines.${i}.accountId`)} aria-invalid={!!le?.accountId} className="min-w-64">
                        <option value="">Choose…</option>
                        {ledgers.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
                      </Select>
                      {le?.accountId && <p className="mt-1 text-xs text-danger">{le.accountId.message}</p>}
                    </Td>
                    <Td><Input aria-label={`Line ${i + 1} description`} {...register(`lines.${i}.description`)} /></Td>
                    <Td>
                      <Input aria-label={`Line ${i + 1} debit`} inputMode="decimal" className="text-right font-mono" {...register(`lines.${i}.debit`)} aria-invalid={!!le?.debit} />
                      {le?.debit && <p className="mt-1 text-right text-xs text-danger">{le.debit.message}</p>}
                    </Td>
                    <Td>
                      <Input aria-label={`Line ${i + 1} credit`} inputMode="decimal" className="text-right font-mono" {...register(`lines.${i}.credit`)} aria-invalid={!!le?.credit} />
                      {le?.credit && <p className="mt-1 text-right text-xs text-danger">{le.credit.message}</p>}
                    </Td>
                    <Td>
                      <Button variant="ghost" size="icon" aria-label={`Remove line ${i + 1}`} disabled={fields.length <= 2} onClick={() => remove(i)}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </Td>
                  </tr>
                );
              })}
              <tr className="bg-page font-semibold">
                <Td colSpan={3}>
                  <Button size="sm" variant="secondary" onClick={() => append({ ...emptyLine })}><Plus className="h-4 w-4" /> Add line</Button>
                </Td>
                <Td className="text-right font-mono">{formatINR(paiseToString(dr))}</Td>
                <Td className="text-right font-mono">{formatINR(paiseToString(cr))}</Td>
                <Td />
              </tr>
            </tbody>
          </Table>
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3">
            {balanced ? (
              <p className="flex items-center gap-1.5 text-sm text-emerald"><CheckCircle2 className="h-4 w-4" /> Balanced</p>
            ) : (
              <p className="flex items-center gap-1.5 text-sm text-danger"><XCircle className="h-4 w-4" />
                {invalid ? "Fix invalid amounts" : dr === 0n ? "Enter amounts" : `Difference ₹${formatINR(paiseToString(diff < 0n ? -diff : diff))} (${diff > 0n ? "debit" : "credit"} side higher)`}
                <span className="text-muted"> · unbalanced drafts can be saved but not submitted</span>
              </p>
            )}
            {e.lines?.root?.message && <p className="text-sm text-danger">{e.lines.root.message}</p>}
            <Button type="submit" loading={save.isPending}>{existing ? "Save changes" : "Save draft"}</Button>
          </div>
        </Card>
      </form>
    </div>
  );
}

/** POST with an Idempotency-Key so a double-click or retry cannot create two journals. */
function fetchWithKey(body: unknown, key: string): Promise<JournalDetail> {
  return request<JournalDetail>("/accounting/journals", { method: "POST", body, idempotencyKey: key });
}

// ------------------------------------------------------------------ view

const ACTION_META: Record<Exclude<JournalAction, "EDIT" | "REVERSE">, { label: string; tone: "primary" | "success" | "danger"; description: string; comment?: "optional" | "required" }> = {
  SUBMIT: { label: "Submit for approval", tone: "primary", description: "A different user with approval rights must approve and post it." },
  APPROVE: { label: "Approve", tone: "success", description: "Your approval is recorded. The journal can then be posted.", comment: "optional" },
  REJECT: { label: "Reject", tone: "danger", description: "Returns the journal to draft so the preparer can correct it.", comment: "required" },
  POST: { label: "Post to ledger", tone: "success", description: "Posting is final: the entry gets a voucher number and can only be corrected by a reversal." },
  CANCEL: { label: "Cancel journal", tone: "danger", description: "The journal will never be posted. It stays on record." },
};

function JournalView({ journal: j, onEdit }: { journal: JournalDetail; onEdit: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [pending, setPending] = useState<keyof typeof ACTION_META | null>(null);
  const [reversing, setReversing] = useState(false);
  const meta = pending ? ACTION_META[pending] : null;

  const run = async (comment: string) => {
    const verb = pending!.toLowerCase();
    const body = pending === "APPROVE" || pending === "REJECT" ? { reason: comment || null } : undefined;
    const updated = await api.post<JournalDetail>(`/accounting/journals/${j.id}/${verb}`, body);
    queryClient.setQueryData(["accounting", "journal", j.id], updated);
    queryClient.invalidateQueries({ queryKey: ["accounting"] });
    queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    toast.success(pending === "POST" ? `Posted as ${updated.entryNumber}` : `Journal ${humanize(updated.status).toLowerCase()}`);
  };

  const trail = [
    { label: "Prepared", who: j.preparedBy, at: j.createdAt },
    { label: "Submitted", who: j.submittedBy, at: j.submittedAt },
    { label: "Approved", who: j.approvedBy, at: j.approvedAt, note: j.approvalComment },
    { label: "Posted", who: j.postedBy, at: j.postedAt },
  ];

  return (
    <div>
      <PageHeader
        title={j.entryNumber ?? (j.status === "DRAFT" ? "Draft journal" : j.status === "CANCELLED" ? "Cancelled journal" : "Unposted journal")}
        description={`${formatDate(j.entryDate)} · FY ${j.financialYear}${j.periodStatus === "CLOSED" ? " · period closed" : ""}`}
        actions={<Link to={`/accounting?clientId=${j.clientId}`}><Button variant="secondary"><ArrowLeft className="h-4 w-4" /> Journals</Button></Link>}
      />
      <Card className="mb-5 flex flex-wrap items-center justify-between gap-3 px-5 py-4">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={j.status} />
          {j.entryType !== "GENERAL" && <Badge>{humanize(j.entryType)}</Badge>}
          {j.reversalOfId && <Link to={`/accounting/journals/${j.reversalOfId}`} className="text-sm text-emerald hover:underline">Reverses {j.reversalOfNumber}</Link>}
          {j.reversedById && <Link to={`/accounting/journals/${j.reversedById}`} className="text-sm text-danger hover:underline">Reversed by {j.reversedByNumber ?? "draft reversal"}</Link>}
        </div>
        <div className="flex flex-wrap gap-2">
          {j.allowedActions.includes("EDIT") && <Button size="sm" variant="secondary" onClick={onEdit}>Edit</Button>}
          {(["SUBMIT", "APPROVE", "POST", "REJECT", "CANCEL"] as const).filter((a) => j.allowedActions.includes(a)).map((a) => (
            <Button key={a} size="sm" variant={a === "REJECT" || a === "CANCEL" ? "secondary" : ACTION_META[a].tone}
              className={a === "REJECT" || a === "CANCEL" ? "text-danger" : undefined} onClick={() => setPending(a)}>
              {ACTION_META[a].label}
            </Button>
          ))}
          {j.allowedActions.includes("REVERSE") && <Button size="sm" variant="secondary" onClick={() => setReversing(true)}>Reverse</Button>}
        </div>
      </Card>

      {j.rejectionReason && j.status === "DRAFT" && (
        <p role="alert" className="mb-5 rounded-md bg-danger-soft px-4 py-3 text-sm text-danger">
          Rejected by {j.rejectedBy?.fullName} on {formatDateTime(j.rejectedAt)}: “{j.rejectionReason}”
        </p>
      )}

      <Card className="mb-5">
        <div className="px-5 py-4">
          <p className="text-sm whitespace-pre-wrap">{j.narration}</p>
          {j.reference && <p className="mt-1 text-xs text-muted">Reference: {j.reference}</p>}
        </div>
        <Table>
          <thead><tr><Th>#</Th><Th>Account</Th><Th>Description</Th><Th className="text-right">Debit (₹)</Th><Th className="text-right">Credit (₹)</Th></tr></thead>
          <tbody>
            {j.lines.map((l) => (
              <tr key={l.id}>
                <Td className="text-muted">{l.lineNo}</Td>
                <Td><span className="font-mono text-xs">{l.accountCode}</span> · {l.accountName}</Td>
                <Td className="text-muted">{l.description ?? ""}</Td>
                <Td className="text-right font-mono tabular-nums">{formatINR(l.debit, { blankZero: true })}</Td>
                <Td className="text-right font-mono tabular-nums">{formatINR(l.credit, { blankZero: true })}</Td>
              </tr>
            ))}
            <tr className="bg-page font-semibold">
              <Td colSpan={3}>{j.balanced ? "Total" : <span className="text-danger">Total — not balanced</span>}</Td>
              <Td className="text-right font-mono">{formatINR(j.totalDebit)}</Td>
              <Td className="text-right font-mono">{formatINR(j.totalCredit)}</Td>
            </tr>
          </tbody>
        </Table>
      </Card>

      <Card>
        <CardHeader title="Approval trail" description="Maker-checker: the preparer cannot approve or post." />
        <ol className="grid gap-4 p-5 sm:grid-cols-4">
          {trail.map((t) => (
            <li key={t.label} className={t.who ? "" : "opacity-50"}>
              <p className="text-xs font-medium text-muted">{t.label}</p>
              <p className="text-sm">{t.who?.fullName ?? "—"}</p>
              <p className="text-xs text-muted">{t.at && t.who ? formatDateTime(t.at) : ""}</p>
              {t.note && <p className="mt-1 text-xs italic">“{t.note}”</p>}
            </li>
          ))}
        </ol>
      </Card>

      {meta && (
        <ConfirmDialog open onOpenChange={(o) => !o && setPending(null)} title={meta.label} description={meta.description}
          confirmLabel={meta.label} tone={meta.tone}
          commentLabel={meta.comment === "required" ? "Reason" : meta.comment === "optional" ? "Comment (optional)" : undefined}
          commentRequired={meta.comment === "required"} onConfirm={run} />
      )}
      {reversing && <ReverseDialog journal={j} onClose={() => setReversing(false)} onCreated={(rid) => navigate(`/accounting/journals/${rid}`)} />}
    </div>
  );
}

function ReverseDialog({ journal, onClose, onCreated }: { journal: JournalDetail; onClose: () => void; onCreated: (id: string) => void }) {
  const queryClient = useQueryClient();
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10) < journal.entryDate ? journal.entryDate : new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => setError(null), [date, reason]);
  const submit = async () => {
    if (!reason.trim()) { setError("Give a reason"); return; }
    setBusy(true);
    try {
      const rev = await api.post<JournalDetail>(`/accounting/journals/${journal.id}/reverse`, { reversalDate: date, reason: reason.trim() });
      queryClient.invalidateQueries({ queryKey: ["accounting"] });
      toast.success("Reversal created as a draft. Submit it for approval.");
      onCreated(rev.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`Reverse ${journal.entryNumber}`}
      description="Creates a mirror-image draft (debits and credits swapped). It must be submitted, approved and posted like any journal."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="danger" loading={busy} onClick={submit}>Create reversal</Button></>}>
      <div className="space-y-4">
        <Field label="Reversal date" htmlFor="rev-date" required><Input id="rev-date" type="date" value={date} min={journal.entryDate} onChange={(e) => setDate(e.target.value)} /></Field>
        <Field label="Reason" htmlFor="rev-reason" required error={error ?? undefined}>
          <Textarea id="rev-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} aria-invalid={!!error} />
        </Field>
      </div>
    </Dialog>
  );
}
