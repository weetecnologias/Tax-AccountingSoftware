import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ShieldAlert, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { ConfirmDialog, ErrorState, Skeleton } from "@/components/common";
import { Button, Card, CardHeader, Field, Input, Select } from "@/components/ui";
import { useStates } from "@/hooks/useReference";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { formatDateTime } from "@/lib/utils";
import type { OrganisationSettings } from "@/types/accounting";
import { isValidGstin, PAN_RE, PINCODE_RE, upper } from "@/validation/ids";

const schema = z.object({
  displayName: z.string().trim().min(1, "Required").max(200),
  legalName: z.string().max(200),
  tagline: z.string().max(200),
  addressLine1: z.string().max(200),
  addressLine2: z.string().max(200),
  city: z.string().max(100),
  stateCode: z.string(),
  pincode: z.string().refine((v) => v === "" || PINCODE_RE.test(v), "6 digits, not starting with 0"),
  gstin: z.preprocess(upper, z.string().refine((v) => v === "" || isValidGstin(v), "Invalid GSTIN")),
  pan: z.preprocess(upper, z.string().refine((v) => v === "" || PAN_RE.test(v), "PAN must look like AAAAA9999A")),
  email: z.string().trim().refine((v) => v === "" || z.email().safeParse(v).success, "Enter a valid email"),
  phone: z.string().max(20),
  website: z.string().max(200),
  reportFooter: z.string().max(500),
});
type Values = z.input<typeof schema>;
type Parsed = z.output<typeof schema>;

export function OrganisationTab() {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const states = useStates();
  const editable = can("SETTINGS_WRITE");
  const q = useQuery({ queryKey: ["settings", "organisation"], queryFn: () => api.get<OrganisationSettings>("/settings/organisation") });
  const [selfApproval, setSelfApproval] = useState(false);
  const [confirmPolicy, setConfirmPolicy] = useState<Parsed | null>(null);
  const form = useForm<Values, unknown, Parsed>({ resolver: zodResolver(schema) });
  const { register, handleSubmit, reset, formState, setError } = form;
  const e = formState.errors;

  useEffect(() => {
    const s = q.data;
    if (!s) return;
    setSelfApproval(s.allowSelfApproval);
    reset({
      displayName: s.displayName, legalName: s.legalName ?? "", tagline: s.tagline ?? "", addressLine1: s.addressLine1 ?? "",
      addressLine2: s.addressLine2 ?? "", city: s.city ?? "", stateCode: s.stateCode ?? "", pincode: s.pincode ?? "",
      gstin: s.gstin ?? "", pan: s.pan ?? "", email: s.email ?? "", phone: s.phone ?? "", website: s.website ?? "",
      reportFooter: s.reportFooter ?? "",
    });
  }, [q.data, reset]);

  const save = useMutation({
    mutationFn: (v: Parsed) => api.put<OrganisationSettings>("/settings/organisation", {
      displayName: v.displayName, legalName: nullIfBlank(v.legalName), tagline: nullIfBlank(v.tagline),
      addressLine1: nullIfBlank(v.addressLine1), addressLine2: nullIfBlank(v.addressLine2), city: nullIfBlank(v.city),
      stateCode: nullIfBlank(v.stateCode), pincode: nullIfBlank(v.pincode), gstin: nullIfBlank(v.gstin as string),
      pan: nullIfBlank(v.pan as string), email: nullIfBlank(v.email), phone: nullIfBlank(v.phone),
      website: nullIfBlank(v.website), reportFooter: nullIfBlank(v.reportFooter), allowSelfApproval: selfApproval,
      version: q.data!.version,
    }),
    onSuccess: (s) => { queryClient.setQueryData(["settings", "organisation"], s); queryClient.invalidateQueries({ queryKey: ["accounting"] }); toast.success("Settings saved"); },
    onError: (err) => applyServerErrors(err, setError, Object.keys(schema.shape)),
  });

  const submit = (v: Parsed) => {
    if (q.data && selfApproval !== q.data.allowSelfApproval) setConfirmPolicy(v);
    else save.mutate(v);
  };

  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton className="h-96" />;

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-5">
      <Card>
        <CardHeader title="Approval policy" description="Applies to journals, invoices and bank reconciliation sign-off." />
        <div className="space-y-3 p-5">
          <label className="flex cursor-pointer items-start gap-3 rounded-md border border-line p-3">
            <input type="radio" name="policy" className="mt-1 accent-emerald" checked={!selfApproval} disabled={!editable}
              onChange={() => setSelfApproval(false)} />
            <span>
              <span className="flex items-center gap-1.5 font-medium"><ShieldCheck className="h-4 w-4 text-emerald" /> A different person must approve (recommended)</span>
              <span className="block text-sm text-muted">The preparer can never approve or post their own work. This is the maker-checker control in the product specification.</span>
            </span>
          </label>
          <label className="flex cursor-pointer items-start gap-3 rounded-md border border-line p-3">
            <input type="radio" name="policy" className="mt-1 accent-emerald" checked={selfApproval} disabled={!editable}
              onChange={() => setSelfApproval(true)} />
            <span>
              <span className="flex items-center gap-1.5 font-medium"><ShieldAlert className="h-4 w-4 text-warning" /> The same person may approve their own work</span>
              <span className="block text-sm text-muted">Approval is still required as a separate step. Every self-approval is flagged in the audit log.</span>
            </span>
          </label>
        </div>
      </Card>

      <Card>
        <CardHeader title="Report letterhead" description="Printed at the top of financial reports. Placeholder values can be replaced at any time." />
        <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-2">
          <Field label="Display name" htmlFor="o-name" required error={e.displayName?.message}><Input id="o-name" disabled={!editable} {...register("displayName")} aria-invalid={!!e.displayName} /></Field>
          <Field label="Legal name" htmlFor="o-legal" hint="Shown on reports instead of the display name when set"><Input id="o-legal" disabled={!editable} {...register("legalName")} /></Field>
          <Field label="Tagline" htmlFor="o-tagline" className="md:col-span-2"><Input id="o-tagline" disabled={!editable} {...register("tagline")} /></Field>
          <Field label="Address line 1" htmlFor="o-a1"><Input id="o-a1" disabled={!editable} {...register("addressLine1")} /></Field>
          <Field label="Address line 2" htmlFor="o-a2"><Input id="o-a2" disabled={!editable} {...register("addressLine2")} /></Field>
          <Field label="City" htmlFor="o-city"><Input id="o-city" disabled={!editable} {...register("city")} /></Field>
          <Field label="State" htmlFor="o-state">
            <Select id="o-state" disabled={!editable} {...register("stateCode")}>
              <option value="">—</option>
              {states.data?.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
            </Select>
          </Field>
          <Field label="PIN code" htmlFor="o-pin" error={e.pincode?.message}><Input id="o-pin" maxLength={6} disabled={!editable} {...register("pincode")} aria-invalid={!!e.pincode} /></Field>
          <Field label="GSTIN" htmlFor="o-gstin" error={e.gstin?.message}><Input id="o-gstin" maxLength={15} className="font-mono uppercase" disabled={!editable} {...register("gstin")} aria-invalid={!!e.gstin} /></Field>
          <Field label="PAN" htmlFor="o-pan" error={e.pan?.message}><Input id="o-pan" maxLength={10} className="font-mono uppercase" disabled={!editable} {...register("pan")} aria-invalid={!!e.pan} /></Field>
          <Field label="Email" htmlFor="o-email" error={e.email?.message}><Input id="o-email" type="email" disabled={!editable} {...register("email")} aria-invalid={!!e.email} /></Field>
          <Field label="Phone" htmlFor="o-phone"><Input id="o-phone" disabled={!editable} {...register("phone")} /></Field>
          <Field label="Website" htmlFor="o-web"><Input id="o-web" disabled={!editable} {...register("website")} /></Field>
          <Field label="Report footer" htmlFor="o-footer" className="md:col-span-2"><Input id="o-footer" disabled={!editable} {...register("reportFooter")} /></Field>
        </div>
      </Card>

      <div className="flex items-center justify-between">
        <p className="text-xs text-muted">Last updated {formatDateTime(q.data.updatedAt)}</p>
        {editable ? <Button type="submit" loading={save.isPending}>Save settings</Button>
          : <p className="text-sm text-muted">Only administrators can change these settings.</p>}
      </div>

      <ConfirmDialog open={!!confirmPolicy} onOpenChange={(o) => !o && setConfirmPolicy(null)}
        title="Change the approval policy"
        description={selfApproval
          ? "Users will be able to approve their own journals, invoices and reconciliations. Each such approval is flagged in the audit log."
          : "A different person will again be required to approve every journal, invoice and reconciliation."}
        confirmLabel="Change policy" tone={selfApproval ? "danger" : "primary"}
        onConfirm={async () => { await save.mutateAsync(confirmPolicy!); }} />
    </form>
  );
}
