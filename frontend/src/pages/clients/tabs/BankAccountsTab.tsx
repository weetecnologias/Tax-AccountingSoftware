import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { EmptyState, ErrorState, LoadingRows } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { api } from "@/lib/api";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { humanize } from "@/lib/utils";
import type { BankAccount } from "@/types/api";
import { IFSC_RE, upper } from "@/validation/ids";

const ACCOUNT_TYPES = ["SAVINGS", "CURRENT", "OD", "CC", "FD", "OTHER"];

function schemaFor(isNew: boolean) {
  return z.object({
    bankName: z.string().trim().min(1, "Required").max(120),
    branch: z.string().max(120),
    accountHolder: z.string().trim().min(1, "Required").max(200),
    accountNumber: z.string().trim().refine(
      (v) => (v === "" && !isNew) || /^[0-9]{6,18}$/.test(v.replace(/ /g, "")),
      isNew ? "6 to 18 digits" : "6 to 18 digits, or leave blank to keep the current number",
    ),
    ifsc: z.preprocess(upper, z.string().regex(IFSC_RE, "11 characters, e.g. HDFC0001234")),
    accountType: z.string().min(1),
    active: z.boolean(),
  });
}
type Values = z.input<ReturnType<typeof schemaFor>>;
type Parsed = z.output<ReturnType<typeof schemaFor>>;

export function BankAccountsTab({ clientId, editable }: { clientId: string; editable: boolean }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<BankAccount | "new" | null>(null);
  const q = useQuery({ queryKey: ["client", clientId, "banks"], queryFn: () => api.get<BankAccount[]>(`/clients/${clientId}/bank-accounts`) });

  return (
    <Card>
      <CardHeader title="Bank accounts" description="Account numbers are encrypted and shown masked unless you have permission to view them."
        actions={editable && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> Add account</Button>} />
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>Bank</Th><Th>Account holder</Th><Th>Account number</Th><Th>IFSC</Th><Th>Type</Th><Th>Status</Th><Th /></tr></thead>
            <tbody>
              {q.isPending ? <LoadingRows cols={7} rows={2} /> : q.data?.map((b) => (
                <tr key={b.id}>
                  <Td><p className="font-medium">{b.bankName}</p>{b.branch && <p className="text-xs text-muted">{b.branch}</p>}</Td>
                  <Td>{b.accountHolder}</Td>
                  <Td className="font-mono">{b.accountNumber}</Td>
                  <Td className="font-mono">{b.ifsc}</Td>
                  <Td>{humanize(b.accountType)}</Td>
                  <Td>{b.active ? <Badge tone="success">Active</Badge> : <Badge>Inactive</Badge>}</Td>
                  <Td className="text-right">{editable && <Button variant="ghost" size="icon" aria-label="Edit account" onClick={() => setEditing(b)}><Pencil className="h-4 w-4" /></Button>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {q.data?.length === 0 && <EmptyState title="No bank accounts" />}
        </>
      )}
      {editing && <BankDialog clientId={clientId} existing={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={() => { queryClient.invalidateQueries({ queryKey: ["client", clientId, "banks"] }); setEditing(null); }} />}
    </Card>
  );
}

function BankDialog({ clientId, existing, onClose, onSaved }: { clientId: string; existing: BankAccount | null; onClose: () => void; onSaved: () => void }) {
  const schema = schemaFor(!existing);
  const { register, handleSubmit, formState, setError } = useForm<Values, unknown, Parsed>({
    resolver: zodResolver(schema),
    defaultValues: { bankName: existing?.bankName ?? "", branch: existing?.branch ?? "", accountHolder: existing?.accountHolder ?? "",
      accountNumber: "", ifsc: existing?.ifsc ?? "", accountType: existing?.accountType ?? "CURRENT", active: existing?.active ?? true },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: Parsed) => {
      const body = { ...v, branch: nullIfBlank(v.branch), accountNumber: nullIfBlank(v.accountNumber) };
      return existing ? api.put(`/clients/${clientId}/bank-accounts/${existing.id}`, body) : api.post(`/clients/${clientId}/bank-accounts`, body);
    },
    onSuccess: () => { toast.success("Bank account saved"); onSaved(); },
    onError: (err) => applyServerErrors(err, setError, Object.keys(schema.shape)),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? "Edit bank account" : "Add bank account"}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <Field label="Bank name" htmlFor="b-bank" required error={e.bankName?.message}><Input id="b-bank" {...register("bankName")} aria-invalid={!!e.bankName} /></Field>
        <Field label="Branch" htmlFor="b-branch"><Input id="b-branch" {...register("branch")} /></Field>
        <Field label="Account holder" htmlFor="b-holder" required error={e.accountHolder?.message}><Input id="b-holder" {...register("accountHolder")} aria-invalid={!!e.accountHolder} /></Field>
        <Field label="Account number" htmlFor="b-number" required={!existing} error={e.accountNumber?.message}
          hint={existing ? `Current: ${existing.accountNumber}. Leave blank to keep it.` : undefined}>
          <Input id="b-number" inputMode="numeric" autoComplete="off" className="font-mono" {...register("accountNumber")} aria-invalid={!!e.accountNumber} />
        </Field>
        <Field label="IFSC" htmlFor="b-ifsc" required error={e.ifsc?.message}><Input id="b-ifsc" maxLength={11} className="font-mono uppercase" {...register("ifsc")} aria-invalid={!!e.ifsc} /></Field>
        <Field label="Account type" htmlFor="b-type"><Select id="b-type" {...register("accountType")}>{ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" {...register("active")} className="h-4 w-4 accent-emerald" /> Active</label>
      </form>
    </Dialog>
  );
}
