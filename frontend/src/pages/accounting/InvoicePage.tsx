import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, FileDown, FileMinus, Plus, Trash2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useFieldArray, useForm, useWatch } from "react-hook-form";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import { ConfirmDialog, DetailList, ErrorState, PageHeader, Skeleton, StatusBadge } from "@/components/common";
import { Badge, Button, Card, CardHeader, Field, Input, Select, Table, Td, Textarea, Th } from "@/components/ui";
import { useAccounts } from "@/hooks/useAccounting";
import { useStates } from "@/hooks/useReference";
import { ApiError, api, downloadFile, request } from "@/lib/api";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { formatINR, gstLinePreview, paiseToString } from "@/lib/money";
import { formatDate, formatDateTime } from "@/lib/utils";
import type { GstRegistration } from "@/types/api";
import { INVOICE_TYPE_LABEL } from "@/types/accounting";
import type { GstRate, Invoice, InvoiceType } from "@/types/accounting";
import { useParties } from "./PartiesTab";

export function InvoicePage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const [editing, setEditing] = useState(false);
  const inv = useQuery({ queryKey: ["accounting", "invoice", id], queryFn: () => api.get<Invoice>(`/invoices/${id}`), enabled: !!id });
  const originalId = id ? inv.data?.originalInvoiceId ?? null : params.get("original");
  const original = useQuery({ queryKey: ["accounting", "invoice", originalId], queryFn: () => api.get<Invoice>(`/invoices/${originalId}`), enabled: !!originalId });
  if (id && inv.isError) return <ErrorState error={inv.error} onRetry={() => inv.refetch()} />;
  if (id && inv.isPending) return <Skeleton className="h-96" />;
  const clientId = id ? inv.data!.clientId : params.get("clientId");
  const type = (id ? inv.data!.invoiceType : params.get("type") ?? "SALES") as InvoiceType;
  if (!clientId) return <ErrorState error={new Error("Choose a client from the Accounting page first.")} />;
  const isNote = type === "CREDIT_NOTE" || type === "DEBIT_NOTE";
  if ((!id || editing) && isNote) {
    if (!originalId) return <ErrorState error={new Error("A credit or debit note must be raised from a posted invoice.")} />;
    if (original.isError) return <ErrorState error={original.error} onRetry={() => original.refetch()} />;
    if (original.isPending) return <Skeleton className="h-96" />;
  }
  if (!id || editing) return <InvoiceEditor clientId={clientId} type={type} existing={inv.data ?? null} original={original.data ?? null} onDone={() => setEditing(false)} />;
  return <InvoiceView invoice={inv.data!} onEdit={() => setEditing(true)} />;
}

/** Inherited from the original on a note: shown but not editable (a disabled field would drop out of the form values). */
const LOCKED = { tabIndex: -1, "aria-readonly": true, className: "pointer-events-none bg-page text-muted" } as const;

const lineSchema = z.object({
  description: z.string().trim().min(1, "Required").max(300),
  hsnSac: z.string().refine((v) => v === "" || /^\d{4,8}$/.test(v), "4-8 digits"),
  accountId: z.string().min(1, "Choose an account"),
  quantity: z.string().refine((v) => /^\d{1,12}(\.\d{1,3})?$/.test(v) && Number(v) > 0, "Up to 3 decimals, more than 0"),
  unitPrice: z.string().refine((v) => /^\d{1,15}(\.\d{1,2})?$/.test(v.replace(/,/g, "")), "Up to 2 decimals"),
  gstRate: z.string().min(1, "Choose"),
});
const schema = z.object({
  invoiceDate: z.string().min(1, "Required"),
  dueDate: z.string(),
  partyId: z.string().min(1, "Choose a party"),
  gstRegistrationId: z.string(),
  placeOfSupply: z.string(),
  reverseCharge: z.boolean(),
  supplierInvoiceNumber: z.string().max(16),
  notes: z.string().max(1000),
  noteReason: z.string().max(500),
  lines: z.array(lineSchema).min(1),
});
type Values = z.infer<typeof schema>;

function InvoiceEditor({ clientId, type, existing, original, onDone }: {
  clientId: string; type: InvoiceType; existing: Invoice | null; original: Invoice | null; onDone: () => void;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Credit notes sit on the sales side, debit notes on the purchase side.
  const sales = type === "SALES" || type === "CREDIT_NOTE";
  const note = type === "CREDIT_NOTE" || type === "DEBIT_NOTE";
  const noteLabel = type === "CREDIT_NOTE" ? "credit note" : "debit note";
  const parties = useParties(clientId);
  const accounts = useAccounts(clientId);
  const states = useStates();
  const regs = useQuery({ queryKey: ["client", clientId, "gst"], queryFn: () => api.get<GstRegistration[]>(`/clients/${clientId}/gst-registrations`) });
  const key = useRef(crypto.randomUUID());
  const activeRegs = (regs.data ?? []).filter((r) => r.status === "ACTIVE");

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: existing ? {
      invoiceDate: existing.invoiceDate, dueDate: existing.dueDate ?? "", partyId: existing.partyId,
      gstRegistrationId: existing.gstRegistrationId ?? "", placeOfSupply: existing.placeOfSupply, reverseCharge: existing.reverseCharge,
      supplierInvoiceNumber: existing.supplierInvoiceNumber ?? "", notes: existing.notes ?? "", noteReason: existing.noteReason ?? "",
      lines: existing.lines.map((l) => ({ description: l.description, hsnSac: l.hsnSac ?? "", accountId: l.accountId,
        quantity: l.quantity, unitPrice: l.unitPrice, gstRate: l.gstRate })),
    } : original ? {
      // A new note starts as a copy of the original's lines; reduce the quantities or rates to what is being credited or debited.
      invoiceDate: new Date().toISOString().slice(0, 10) < original.invoiceDate ? original.invoiceDate : new Date().toISOString().slice(0, 10),
      dueDate: "", partyId: original.partyId, gstRegistrationId: original.gstRegistrationId ?? "", placeOfSupply: original.placeOfSupply,
      reverseCharge: original.reverseCharge, supplierInvoiceNumber: "", notes: "", noteReason: "",
      lines: original.lines.map((l) => ({ description: l.description, hsnSac: l.hsnSac ?? "", accountId: l.accountId,
        quantity: l.quantity, unitPrice: l.unitPrice, gstRate: l.gstRate })),
    } : { invoiceDate: new Date().toISOString().slice(0, 10), dueDate: "", partyId: "", gstRegistrationId: "", placeOfSupply: "",
      reverseCharge: false, supplierInvoiceNumber: "", notes: "", noteReason: "", lines: [{ description: "", hsnSac: "", accountId: "", quantity: "1", unitPrice: "", gstRate: "18" }] },
  });
  const { register, control, handleSubmit, formState, setError, setValue } = form;
  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const w = useWatch({ control });
  const e = formState.errors;
  const date = w.invoiceDate ?? "";
  const rates = useQuery({ queryKey: ["gst-rates", date], queryFn: () => api.get<GstRate[]>(`/invoices/gst-rates?date=${date}`), enabled: !!date });

  // Native <select>s cannot hold a value before their options exist: re-apply form values once the lists load.
  const { reset, getValues } = form;
  const synced = useRef(false);
  useEffect(() => {
    if (synced.current || !(rates.data && accounts.data && parties.data && regs.data)) return;
    synced.current = true;
    const values = getValues();
    const active = regs.data.filter((r) => r.status === "ACTIVE");
    if (sales && !values.gstRegistrationId && active.length === 1) values.gstRegistrationId = active[0].id;
    reset(values, { keepDirty: true, keepErrors: true });
  }, [rates.data, accounts.data, parties.data, regs.data, reset, getValues, sales]);

  const eligibleParties = (parties.data ?? []).filter((p) => p.active && (p.partyType === "BOTH" || p.partyType === (sales ? "CUSTOMER" : "VENDOR")));
  const lineAccounts = (accounts.data ?? []).filter((a) => !a.group && a.active && (sales ? a.accountType === "INCOME" : a.accountType === "EXPENSE" || a.accountType === "ASSET"))
    .sort((a, b) => a.code.localeCompare(b.code));
  const party = eligibleParties.find((p) => p.id === w.partyId);
  const reg = activeRegs.find((r) => r.id === w.gstRegistrationId) ?? (activeRegs.length === 1 && !w.gstRegistrationId ? activeRegs[0] : undefined);
  const pos = w.placeOfSupply || (sales ? party?.stateCode : reg?.stateCode ?? party?.stateCode) || "";
  const inter = sales ? !!reg && !!pos && reg.stateCode !== pos : !!party?.stateCode && !!pos && party.stateCode !== pos;

  const totals = useMemo(() => {
    let taxable = 0n, cgst = 0n, sgst = 0n, igst = 0n, ok = true;
    for (const l of w.lines ?? []) {
      const p = gstLinePreview(l?.quantity ?? "", l?.unitPrice ?? "", l?.gstRate ?? "0", inter);
      if (!p) { ok = false; continue; }
      taxable += p.taxable; cgst += p.cgst; sgst += p.sgst; igst += p.igst;
    }
    return { taxable, cgst, sgst, igst, grand: taxable + cgst + sgst + igst, ok };
  }, [w.lines, inter]);

  const save = useMutation({
    mutationFn: (v: Values) => {
      const body = {
        clientId, invoiceType: type, invoiceDate: v.invoiceDate, dueDate: nullIfBlank(v.dueDate), partyId: v.partyId,
        gstRegistrationId: nullIfBlank(v.gstRegistrationId) ?? reg?.id ?? null, placeOfSupply: nullIfBlank(v.placeOfSupply),
        reverseCharge: !sales && v.reverseCharge, supplierInvoiceNumber: sales || note ? null : nullIfBlank(v.supplierInvoiceNumber),
        originalInvoiceId: note ? (existing?.originalInvoiceId ?? original?.id ?? null) : null, noteReason: note ? nullIfBlank(v.noteReason) : null,
        notes: nullIfBlank(v.notes), lines: v.lines.map((l) => ({ ...l, unitPrice: l.unitPrice.replace(/,/g, ""), hsnSac: nullIfBlank(l.hsnSac) })),
        version: existing?.version,
      };
      return existing ? api.put<Invoice>(`/invoices/${existing.id}`, body)
        : request<Invoice>("/invoices", { method: "POST", body, idempotencyKey: key.current });
    },
    onSuccess: (inv) => {
      queryClient.invalidateQueries({ queryKey: ["accounting"] });
      queryClient.setQueryData(["accounting", "invoice", inv.id], inv);
      toast.success("Draft saved");
      if (existing) onDone(); else navigate(`/accounting/invoices/${inv.id}`, { replace: true });
    },
    onError: (err) => {
      if (err instanceof ApiError) for (const fe of err.fieldErrors) {
        const m = /^lines\[(\d+)\]\.(\w+)$/.exec(fe.field);
        if (m) setError(`lines.${Number(m[1])}.${m[2] as "description"}`, { message: fe.message });
      }
      applyServerErrors(err, setError, ["invoiceDate", "dueDate", "partyId", "gstRegistrationId", "placeOfSupply", "supplierInvoiceNumber", "reverseCharge", "noteReason", "lines"]);
    },
  });

  return (
    <div>
      <PageHeader title={note ? `${existing ? "Edit draft" : "New"} ${noteLabel} against ${original?.invoiceNumber ?? original?.supplierInvoiceNumber ?? ""}`
          : existing ? "Edit draft invoice" : `New ${sales ? "sales" : "purchase"} invoice`}
        description={note ? `Customer, GSTIN, place of supply and reverse charge follow the original invoice. Up to ₹${formatINR(original?.noteableBalance ?? "0")} can still be ${type === "CREDIT_NOTE" ? "credited" : "debited"}.`
          : sales ? "The invoice number is assigned when the invoice is approved." : "Record the supplier's invoice exactly as received."}
        actions={<Button variant="secondary" onClick={() => (existing ? onDone() : navigate(-1))}><ArrowLeft className="h-4 w-4" /> Back</Button>} />
      <form onSubmit={handleSubmit((v) => {
        if (note && !v.noteReason.trim()) { setError("noteReason", { message: "Give the reason for the note" }); return; }
        save.mutate(v);
      })} noValidate className="space-y-5">
        <Card>
          <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-3">
            <Field label={sales ? "Customer" : "Vendor"} htmlFor="i-party" required error={e.partyId?.message}>
              <Select id="i-party" {...register("partyId")} aria-invalid={!!e.partyId} {...(note ? LOCKED : {})}>
                <option value="">Choose…</option>
                {eligibleParties.map((p) => <option key={p.id} value={p.id}>{p.name}{p.gstin ? ` · ${p.gstin}` : ""}</option>)}
              </Select>
            </Field>
            <Field label={note ? "Note date" : "Invoice date"} htmlFor="i-date" required error={e.invoiceDate?.message}><Input id="i-date" type="date" min={original?.invoiceDate} {...register("invoiceDate")} aria-invalid={!!e.invoiceDate} /></Field>
            <Field label="Due date" htmlFor="i-due" error={e.dueDate?.message} hint={party ? `Default: +${party.creditDays} days` : undefined}><Input id="i-due" type="date" {...register("dueDate")} /></Field>
            <Field label={sales ? "Issued from GSTIN" : "Received at GSTIN"} htmlFor="i-reg" error={e.gstRegistrationId?.message}
              hint={!sales ? "Leave blank if input tax credit cannot be claimed (tax is added to cost)" : undefined}>
              <Select id="i-reg" {...register("gstRegistrationId")} aria-invalid={!!e.gstRegistrationId} {...(note ? LOCKED : {})}>
                {(!sales || activeRegs.length !== 1) && <option value="">{sales ? "Choose…" : "No ITC (not registered)"}</option>}
                {activeRegs.map((r) => <option key={r.id} value={r.id}>{r.gstin} · {r.stateName}</option>)}
              </Select>
            </Field>
            <Field label="Place of supply" htmlFor="i-pos" error={e.placeOfSupply?.message} hint={pos ? (inter ? "Inter-state → IGST" : "Intra-state → CGST + SGST") : undefined}>
              <Select id="i-pos" {...register("placeOfSupply")} {...(note ? LOCKED : {})}>
                <option value="">{pos ? `Auto (${states.data?.find((s) => s.value === pos)?.label ?? pos})` : "Choose…"}</option>
                {states.data?.map((s) => <option key={s.value} value={s.value}>{s.value} · {s.label}</option>)}
              </Select>
            </Field>
            {note ? (
              <Field label="Reason" htmlFor="i-reason" required error={e.noteReason?.message} hint="e.g. goods returned, rate difference, short supply">
                <Input id="i-reason" maxLength={500} {...register("noteReason")} aria-invalid={!!e.noteReason} />
              </Field>
            ) : !sales ? (
              <Field label="Supplier invoice number" htmlFor="i-supno" required error={e.supplierInvoiceNumber?.message}>
                <Input id="i-supno" maxLength={16} {...register("supplierInvoiceNumber")} aria-invalid={!!e.supplierInvoiceNumber} />
              </Field>
            ) : <div />}
            {!sales && !note && (
              <label className="flex items-center gap-2 text-sm md:col-span-3">
                <input type="checkbox" {...register("reverseCharge")} className="h-4 w-4 accent-emerald" onChange={(ev) => setValue("reverseCharge", ev.target.checked)} />
                Reverse charge applies (tax payable by us under RCM)
              </label>
            )}
          </div>
        </Card>

        <Card>
          <CardHeader title="Items" description="Tax shown here is a live preview; the server recalculates it when you save." />
          <Table>
            <thead><tr><Th>Description</Th><Th className="w-24">HSN/SAC</Th><Th>Account</Th><Th className="w-24 text-right">Qty</Th><Th className="w-32 text-right">Rate (₹)</Th><Th className="w-24">GST %</Th><Th className="w-32 text-right">Taxable</Th><Th className="w-28 text-right">Tax</Th><Th className="w-10" /></tr></thead>
            <tbody>
              {fields.map((f, i) => {
                const le = e.lines?.[i];
                const l = w.lines?.[i];
                const p = gstLinePreview(l?.quantity ?? "", l?.unitPrice ?? "", l?.gstRate ?? "0", inter);
                return (
                  <tr key={f.id} className="align-top">
                    <Td><Input aria-label={`Item ${i + 1} description`} {...register(`lines.${i}.description`)} aria-invalid={!!le?.description} />{le?.description && <p className="mt-1 text-xs text-danger">{le.description.message}</p>}</Td>
                    <Td><Input aria-label={`Item ${i + 1} HSN/SAC`} maxLength={8} {...register(`lines.${i}.hsnSac`)} aria-invalid={!!le?.hsnSac} /></Td>
                    <Td>
                      <Select aria-label={`Item ${i + 1} account`} {...register(`lines.${i}.accountId`)} aria-invalid={!!le?.accountId} className="min-w-44">
                        <option value="">Choose…</option>
                        {lineAccounts.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
                      </Select>
                      {le?.accountId && <p className="mt-1 text-xs text-danger">{le.accountId.message}</p>}
                    </Td>
                    <Td><Input aria-label={`Item ${i + 1} quantity`} inputMode="decimal" className="text-right font-mono" {...register(`lines.${i}.quantity`)} aria-invalid={!!le?.quantity} /></Td>
                    <Td><Input aria-label={`Item ${i + 1} rate`} inputMode="decimal" className="text-right font-mono" {...register(`lines.${i}.unitPrice`)} aria-invalid={!!le?.unitPrice} />{le?.unitPrice && <p className="mt-1 text-xs text-danger">{le.unitPrice.message}</p>}</Td>
                    <Td>
                      <Select aria-label={`Item ${i + 1} GST rate`} {...register(`lines.${i}.gstRate`)} aria-invalid={!!le?.gstRate}>
                        {rates.data?.map((r) => <option key={r.rate} value={String(Number(r.rate))}>{Number(r.rate)}%</option>)}
                      </Select>
                      {le?.gstRate && <p className="mt-1 text-xs text-danger">{le.gstRate.message}</p>}
                    </Td>
                    <Td className="pt-5 text-right font-mono tabular-nums">{p ? formatINR(paiseToString(p.taxable)) : "—"}</Td>
                    <Td className="pt-5 text-right font-mono tabular-nums">{p ? formatINR(paiseToString(p.cgst + p.sgst + p.igst)) : "—"}</Td>
                    <Td><Button variant="ghost" size="icon" aria-label={`Remove item ${i + 1}`} disabled={fields.length <= 1} onClick={() => remove(i)}><Trash2 className="h-4 w-4" /></Button></Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
          <div className="flex flex-wrap items-start justify-between gap-4 p-5">
            <div className="space-y-3">
              <Button size="sm" variant="secondary" onClick={() => append({ description: "", hsnSac: "", accountId: "", quantity: "1", unitPrice: "", gstRate: "18" })}><Plus className="h-4 w-4" /> Add item</Button>
              <Field label="Notes" htmlFor="i-notes"><Textarea id="i-notes" rows={2} className="w-80" {...register("notes")} /></Field>
              {rates.data?.some((r) => r.reviewStatus !== "APPROVED") && (
                <p className="max-w-sm text-xs text-muted">GST rate slabs are loaded from the rate master and await tax-expert review before production use.</p>
              )}
            </div>
            <dl className="w-72 space-y-1 text-sm">
              <div className="flex justify-between"><dt className="text-muted">Taxable value</dt><dd className="font-mono">{formatINR(paiseToString(totals.taxable))}</dd></div>
              {inter ? <div className="flex justify-between"><dt className="text-muted">IGST</dt><dd className="font-mono">{formatINR(paiseToString(totals.igst))}</dd></div> : <>
                <div className="flex justify-between"><dt className="text-muted">CGST</dt><dd className="font-mono">{formatINR(paiseToString(totals.cgst))}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">SGST / UTGST</dt><dd className="font-mono">{formatINR(paiseToString(totals.sgst))}</dd></div></>}
              <div className="flex justify-between border-t border-line pt-1 text-base font-semibold"><dt>Total</dt><dd className="font-mono">₹{formatINR(paiseToString(totals.grand))}</dd></div>
              <div className="pt-3 text-right"><Button type="submit" loading={save.isPending}>{existing ? "Save changes" : "Save draft"}</Button></div>
            </dl>
          </div>
        </Card>
      </form>
    </div>
  );
}

const META = {
  SUBMIT: { label: "Submit for approval", tone: "primary" as const, description: "An approver will check the invoice and post it." },
  APPROVE: { label: "Approve & post", tone: "success" as const, description: "Posting is final. A sales invoice receives its number and the accounting entry is recorded." },
  REJECT: { label: "Reject", tone: "danger" as const, description: "Returns the invoice to draft for correction." },
  CANCEL: { label: "Cancel invoice", tone: "danger" as const, description: "The invoice will never be posted. It stays on record." },
};

function InvoiceView({ invoice: i, onEdit }: { invoice: Invoice; onEdit: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [pending, setPending] = useState<keyof typeof META | null>(null);
  const [downloading, setDownloading] = useState(false);
  const sales = i.invoiceType === "SALES" || i.invoiceType === "CREDIT_NOTE";
  const note = i.invoiceType === "CREDIT_NOTE" || i.invoiceType === "DEBIT_NOTE";
  const label = INVOICE_TYPE_LABEL[i.invoiceType];
  const pdf = async () => {
    setDownloading(true);
    try {
      await downloadFile(`/invoices/${i.id}/pdf`, `${i.invoiceNumber ?? "draft"}.pdf`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Download failed");
    } finally {
      setDownloading(false);
    }
  };
  const run = async (reason: string) => {
    const updated = await api.post<Invoice>(`/invoices/${i.id}/${pending!.toLowerCase()}`, pending === "REJECT" ? { reason } : undefined);
    queryClient.setQueryData(["accounting", "invoice", i.id], updated);
    queryClient.invalidateQueries({ queryKey: ["accounting"] });
    toast.success(pending === "APPROVE" ? `Posted${updated.invoiceNumber ? ` as ${updated.invoiceNumber}` : ""}` : "Invoice updated");
  };
  return (
    <div>
      <PageHeader title={(i.invoiceType !== "PURCHASE" ? i.invoiceNumber : i.supplierInvoiceNumber) ?? `Draft ${label.toLowerCase()}`}
        description={`${label} ${sales ? "to" : "from"} ${i.partyName} · ${formatDate(i.invoiceDate)}`}
        actions={<div className="flex gap-2">
          {i.invoiceType !== "PURCHASE" && <Button variant="secondary" loading={downloading} onClick={pdf}><FileDown className="h-4 w-4" /> {i.status === "POSTED" ? "PDF" : "Draft PDF"}</Button>}
          <Link to={`/accounting?clientId=${i.clientId}&tab=invoices`}><Button variant="secondary"><ArrowLeft className="h-4 w-4" /> Invoices</Button></Link>
        </div>} />
      <Card className="mb-5 flex flex-wrap items-center justify-between gap-3 px-5 py-4">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={i.status} />
          {i.reverseCharge && <Badge tone="warning">Reverse charge</Badge>}
          {i.selfApproved && <Badge tone="warning">Self-approved</Badge>}
          {i.journalEntryId && <Link to={`/accounting/journals/${i.journalEntryId}`} className="text-sm text-emerald hover:underline">Journal {i.journalNumber}</Link>}
          {i.originalInvoiceId && <Link to={`/accounting/invoices/${i.originalInvoiceId}`} className="text-sm text-emerald hover:underline">Against {i.originalNumber}</Link>}
        </div>
        <div className="flex flex-wrap gap-2">
          {i.allowedActions.includes("EDIT") && <Button size="sm" variant="secondary" onClick={onEdit}>Edit</Button>}
          {(["CREDIT_NOTE", "DEBIT_NOTE"] as const).filter((a) => i.allowedActions.includes(a)).map((a) => (
            <Button key={a} size="sm" variant="secondary" onClick={() => navigate(`/accounting/invoices/new?clientId=${i.clientId}&type=${a}&original=${i.id}`)}>
              <FileMinus className="h-4 w-4" /> Raise {a === "CREDIT_NOTE" ? "credit" : "debit"} note
            </Button>
          ))}
          {(["SUBMIT", "APPROVE", "REJECT", "CANCEL"] as const).filter((a) => i.allowedActions.includes(a)).map((a) => (
            <Button key={a} size="sm" variant={a === "REJECT" || a === "CANCEL" ? "secondary" : META[a].tone}
              className={a === "REJECT" || a === "CANCEL" ? "text-danger" : undefined} onClick={() => setPending(a)}>{META[a].label}</Button>
          ))}
        </div>
      </Card>
      {i.rejectionReason && i.status === "DRAFT" && <p role="alert" className="mb-5 rounded-md bg-danger-soft px-4 py-3 text-sm text-danger">Rejected: “{i.rejectionReason}”</p>}
      <Card className="mb-5 p-5">
        <DetailList items={[
          { label: sales ? "Customer" : "Vendor", value: <>{i.partyName}{i.partyGstin && <span className="block font-mono text-xs text-muted">{i.partyGstin}</span>}</> },
          { label: sales ? "Issued from" : "Received at", value: i.gstin ? <span className="font-mono">{i.gstin}</span> : "Not registered (no ITC)" },
          { label: "Place of supply", value: `${i.placeOfSupply} · ${i.placeOfSupplyName ?? ""} (${i.supplyType === "INTRA" ? "intra-state" : "inter-state"})` },
          { label: note ? "Note date" : "Invoice date", value: formatDate(i.invoiceDate) },
          ...(note ? [{ label: "Reason", value: i.noteReason ?? "" }] : [{ label: "Due date", value: formatDate(i.dueDate) }]),
          ...(!note && i.status === "POSTED" && i.notesPostedTotal !== "0.00" ? [{ label: sales ? "Credit notes posted" : "Debit notes posted",
            value: `₹${formatINR(i.notesPostedTotal)} · ₹${formatINR(i.noteableBalance)} remaining` }] : []),
          { label: "Prepared by", value: `${i.preparedBy?.fullName ?? ""} · ${formatDateTime(i.createdAt)}` },
          ...(i.approvedBy ? [{ label: "Approved & posted by", value: `${i.approvedBy.fullName} · ${formatDateTime(i.approvedAt)}` }] : []),
        ]} />
      </Card>
      <Card>
        <Table>
          <thead><tr><Th>#</Th><Th>Description</Th><Th>HSN/SAC</Th><Th>Account</Th><Th className="text-right">Qty</Th><Th className="text-right">Rate</Th><Th className="text-right">Taxable</Th><Th className="text-right">GST %</Th><Th className="text-right">{i.supplyType === "INTRA" ? "CGST + SGST" : "IGST"}</Th></tr></thead>
          <tbody>
            {i.lines.map((l) => (
              <tr key={l.id}>
                <Td className="text-muted">{l.lineNo}</Td><Td>{l.description}</Td><Td className="font-mono text-xs">{l.hsnSac ?? "—"}</Td>
                <Td className="text-xs">{l.accountCode} · {l.accountName}</Td>
                <Td className="text-right font-mono">{l.quantity}</Td><Td className="text-right font-mono">{formatINR(l.unitPrice)}</Td>
                <Td className="text-right font-mono">{formatINR(l.taxableValue)}</Td><Td className="text-right">{l.gstRate}%</Td>
                <Td className="text-right font-mono">{i.supplyType === "INTRA" ? `${formatINR(l.cgst)} + ${formatINR(l.sgst)}` : formatINR(l.igst)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <dl className="ml-auto w-72 space-y-1 p-5 text-sm">
          <div className="flex justify-between"><dt className="text-muted">Taxable value</dt><dd className="font-mono">{formatINR(i.taxableTotal)}</dd></div>
          {i.supplyType === "INTER" ? <div className="flex justify-between"><dt className="text-muted">IGST</dt><dd className="font-mono">{formatINR(i.igstTotal)}</dd></div> : <>
            <div className="flex justify-between"><dt className="text-muted">CGST</dt><dd className="font-mono">{formatINR(i.cgstTotal)}</dd></div>
            <div className="flex justify-between"><dt className="text-muted">SGST / UTGST</dt><dd className="font-mono">{formatINR(i.sgstTotal)}</dd></div></>}
          <div className="flex justify-between border-t border-line pt-1 text-base font-semibold"><dt>Total</dt><dd className="font-mono">₹{formatINR(i.grandTotal)}</dd></div>
        </dl>
      </Card>
      {pending && <ConfirmDialog open onOpenChange={(o) => !o && setPending(null)} title={META[pending].label} description={META[pending].description}
        confirmLabel={META[pending].label} tone={META[pending].tone} commentLabel={pending === "REJECT" ? "Reason" : undefined}
        commentRequired={pending === "REJECT"} onConfirm={run} />}
    </div>
  );
}
