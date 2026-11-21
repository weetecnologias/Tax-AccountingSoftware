import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { EmptyState, ErrorState, LoadingRows } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { useStates } from "@/hooks/useReference";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { humanize } from "@/lib/utils";
import type { Party } from "@/types/accounting";
import { isValidGstin, upper } from "@/validation/ids";

export const useParties = (clientId: string) =>
  useQuery({ queryKey: ["accounting", clientId, "parties"], queryFn: () => api.get<Party[]>(`/parties?clientId=${clientId}`) });

export function PartiesTab({ clientId, closed }: { clientId: string; closed: boolean }) {
  const { can } = useAuth();
  const parties = useParties(clientId);
  const [editing, setEditing] = useState<Party | "new" | null>(null);
  const editable = can("ACCOUNTING_WRITE") && !closed;
  return (
    <Card>
      <CardHeader title="Customers and vendors" description="GSTIN sets the state automatically and decides intra- or inter-state tax on invoices."
        actions={editable && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> Add party</Button>} />
      {parties.isError ? <ErrorState error={parties.error} onRetry={() => parties.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>Name</Th><Th>Type</Th><Th>GSTIN</Th><Th>State</Th><Th>Credit days</Th><Th>Status</Th><Th /></tr></thead>
            <tbody>
              {parties.isPending ? <LoadingRows cols={7} rows={3} /> : parties.data?.map((p) => (
                <tr key={p.id}>
                  <Td><p className="font-medium">{p.name}</p>{p.email && <p className="text-xs text-muted">{p.email}</p>}</Td>
                  <Td><Badge tone="info">{humanize(p.partyType)}</Badge></Td>
                  <Td className="font-mono text-xs">{p.gstin ?? <span className="text-muted">Unregistered</span>}</Td>
                  <Td>{p.stateName ?? "—"}</Td>
                  <Td>{p.creditDays}</Td>
                  <Td>{p.active ? <Badge tone="success">Active</Badge> : <Badge>Inactive</Badge>}</Td>
                  <Td className="text-right">{editable && <Button variant="ghost" size="icon" aria-label={`Edit ${p.name}`} onClick={() => setEditing(p)}><Pencil className="h-4 w-4" /></Button>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {parties.data?.length === 0 && <EmptyState title="No customers or vendors yet" description="Add them before creating invoices." />}
        </>
      )}
      {editing && <PartyDialog clientId={clientId} existing={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

const schema = z.object({
  partyType: z.enum(["CUSTOMER", "VENDOR", "BOTH"]),
  name: z.string().trim().min(1, "Required").max(200),
  gstin: z.preprocess(upper, z.string().refine((v) => v === "" || isValidGstin(v), "Invalid GSTIN")),
  stateCode: z.string(),
  email: z.string().trim().refine((v) => v === "" || z.email().safeParse(v).success, "Enter a valid email"),
  phone: z.string().max(20),
  address: z.string().max(500),
  creditDays: z.coerce.number().int().min(0).max(365),
  active: z.boolean(),
}).refine((v) => v.gstin !== "" || v.stateCode !== "", { path: ["stateCode"], message: "Choose the state (needed for place of supply)" });
type Values = z.input<typeof schema>;
type Parsed = z.output<typeof schema>;

function PartyDialog({ clientId, existing, onClose }: { clientId: string; existing: Party | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const states = useStates();
  const { register, handleSubmit, formState, setError } = useForm<Values, unknown, Parsed>({
    resolver: zodResolver(schema),
    defaultValues: {
      partyType: existing?.partyType ?? "CUSTOMER", name: existing?.name ?? "", gstin: existing?.gstin ?? "",
      stateCode: existing?.stateCode ?? "", email: existing?.email ?? "", phone: existing?.phone ?? "",
      address: existing?.address ?? "", creditDays: existing?.creditDays ?? 30, active: existing?.active ?? true,
    },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: Parsed) => {
      const body = { ...v, clientId, gstin: nullIfBlank(v.gstin as string), stateCode: nullIfBlank(v.stateCode),
        email: nullIfBlank(v.email), phone: nullIfBlank(v.phone), address: nullIfBlank(v.address), version: existing?.version };
      return existing ? api.put(`/parties/${existing.id}`, body) : api.post("/parties", body);
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["accounting", clientId, "parties"] }); toast.success("Saved"); onClose(); },
    onError: (err) => applyServerErrors(err, setError, ["name", "gstin", "stateCode", "email", "pan", "creditDays"]),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? `Edit ${existing.name}` : "Add customer or vendor"}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <Field label="Type" htmlFor="p-type"><Select id="p-type" {...register("partyType")}><option value="CUSTOMER">Customer</option><option value="VENDOR">Vendor</option><option value="BOTH">Both</option></Select></Field>
        <Field label="Name" htmlFor="p-name" required error={e.name?.message}><Input id="p-name" {...register("name")} aria-invalid={!!e.name} /></Field>
        <Field label="GSTIN" htmlFor="p-gstin" error={e.gstin?.message} hint="Leave blank for unregistered parties"><Input id="p-gstin" maxLength={15} className="font-mono uppercase" {...register("gstin")} aria-invalid={!!e.gstin} /></Field>
        <Field label="State" htmlFor="p-state" error={e.stateCode?.message} hint="Taken from the GSTIN when present">
          <Select id="p-state" {...register("stateCode")} aria-invalid={!!e.stateCode}>
            <option value="">—</option>
            {states.data?.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </Select>
        </Field>
        <Field label="Email" htmlFor="p-email" error={e.email?.message}><Input id="p-email" type="email" {...register("email")} aria-invalid={!!e.email} /></Field>
        <Field label="Phone" htmlFor="p-phone"><Input id="p-phone" {...register("phone")} /></Field>
        <Field label="Address" htmlFor="p-address" className="sm:col-span-2"><Input id="p-address" {...register("address")} /></Field>
        <Field label="Credit days" htmlFor="p-credit" hint="Default due date = invoice date + credit days"><Input id="p-credit" type="number" min={0} max={365} {...register("creditDays")} /></Field>
        <label className="flex items-center gap-2 self-end pb-2 text-sm"><input type="checkbox" {...register("active")} className="h-4 w-4 accent-emerald" /> Active</label>
      </form>
    </Dialog>
  );
}
